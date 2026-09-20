#!/usr/bin/env python3
"""
Faithful Python port of app/src/main/java/com/hrvrm/app/ppg/PpgSignalProcessor.kt.

Why this exists: tuning PpgSignalProcessor's constants (Elgendi beta, MAD multiplier,
level smoothing, ...) was being done "blind" -- change a constant in Kotlin, push,
wait for CI, ask the user to rebuild/retest on their phone, wait for a video or a
verbal beat-count report, repeat. That loop takes minutes per attempt and only gives
a summary count, not the actual signal.

This script runs the *same algorithm* (kept in lockstep with the Kotlin by hand --
see the docstring on each function pointing back to its Kotlin counterpart) directly
against a raw sample export from the app, locally, in milliseconds. It supports
overriding every tunable constant from the command line, so many parameter
combinations can be tried against the SAME real recording in seconds, and only
the promising ones get ported back into Kotlin and verified for real on-device.

Usage:
    python3 ppg_processor.py samples.json
    python3 ppg_processor.py samples.json --mad-multiplier 6 --beta 0.015
    python3 ppg_processor.py samples.json --dump-events   # per-beat accept/reject detail
    python3 ppg_processor.py samples.json --sweep         # grid-search common knobs

Input JSON shape (matches the app's raw-data export):
    {"samples": [{"timestampMs": 0, "intensity": 123.4}, ...], ...other metadata ignored...}
"""
from __future__ import annotations

import argparse
import itertools
import json
import math
import statistics
import sys
from dataclasses import dataclass, field


def round_half_up(x: float) -> int:
    """Matches Kotlin's Double.roundToInt() (round-half-up), not Python's banker's round()."""
    return math.floor(x + 0.5)


@dataclass
class Sample:
    timestamp_ms: int
    intensity: float


@dataclass
class IbiEvent:
    at_ms: int
    ibi_ms: int
    rejection_reason: str | None  # None, "OUT_OF_RANGE", or "IRREGULAR"

    @property
    def accepted(self) -> bool:
        return self.rejection_reason is None


@dataclass
class ProcessingResult:
    filtered_signal: list[float]
    beat_timestamps_ms: list[int]
    raw_ibi_ms: list[int]
    clean_ibi_ms: list[int]
    rejected_beat_count: int
    ibi_events: list[IbiEvent]


@dataclass
class Params:
    min_bpm: float = 35.0
    max_bpm: float = 200.0
    peak_refractory_ms: int = 400

    elgendi_peak_window_ms: float = 111.0
    elgendi_beat_window_ms: float = 667.0
    elgendi_threshold_beta: float = 0.02
    # A block's peak must be at least this fraction of the EWMA of recent accepted peak
    # amplitudes to count as a beat. Root cause this targets (found by diagnosing a real
    # recording where ~70% of beats were rejected): maBeat is a moving average over one
    # beat-length window, so right after a strong systolic peak it can decay toward zero
    # during the quiet trough before the next beat -- and once it does, even the tiny
    # dicrotic notch bump clears `maPeak > maBeat + alpha`, producing a spurious *second*
    # detected "beat" per real cardiac cycle. Width alone (Elgendi's own discriminator)
    # doesn't catch this: the notch block can be just as wide as a real peak's block, only
    # much shorter. Gating on amplitude relative to recent real peaks does.
    peak_min_relative_amplitude: float = 0.35
    peak_amplitude_smoothing: float = 0.3

    artifact_baseline_beats: int = 3
    artifact_level_smoothing: float = 0.6
    artifact_recent_window: int = 5
    # Fraction of the current rhythm level, not a fixed ms value: an absolute floor tuned
    # against ~600-700ms IBIs (high HR) is far too tight at ~1000-1300ms IBIs (low HR) --
    # the same natural swing in ms is a much bigger deal, in ms, at a slower heart rate.
    # 0.25 was checked against a real recording's actual smooth, gradual (respiratory
    # sinus arrhythmia-like) beat-to-beat drift once the spurious notch-beats above were
    # removed: adjacent accepted steps were ~4% of level at the median, ~12% at the 90th
    # percentile, so 25% leaves real headroom without reopening the door to genuine
    # artifacts (a missed/doubled beat still jumps by roughly +-100%).
    artifact_tolerance_floor_fraction: float = 0.25
    artifact_mad_multiplier: float = 4.5
    # After this many consecutive rejections, reject_artifacts() gives up defending the old
    # reference level and resyncs it -- see reject_artifacts()'s docstring for the real
    # recording evidence (rejections clustering on lengthening/exhale-phase beats, sharing
    # one frozen level across a 2-6-beat run) behind this.
    artifact_resync_after_rejects: int = 3
    # How many recent raw (not just accepted) IBIs the resync median is taken over.
    artifact_resync_window: int = 5


def moving_average(values: list[float], window_samples: int) -> list[float]:
    """Kotlin: PpgSignalProcessor.movingAverage -- centered moving average, clamped at edges."""
    if window_samples <= 1:
        return list(values)
    n = len(values)
    half = window_samples // 2
    out = [0.0] * n
    for i in range(n):
        lo = max(0, i - half)
        hi = min(n - 1, i + half)
        out[i] = sum(values[lo:hi + 1]) / (hi - lo + 1)
    return out


def detrend(samples: list[Sample], window_ms: float, sample_rate_hz: float) -> list[float]:
    """Kotlin: PpgSignalProcessor.detrend -- subtract a centered rolling mean."""
    window_samples = max(3, int(window_ms / 1000.0 * sample_rate_hz))
    half = window_samples // 2
    values = [s.intensity for s in samples]
    n = len(values)
    out = [0.0] * n
    for i in range(n):
        lo = max(0, i - half)
        hi = min(n - 1, i + half)
        mean = sum(values[lo:hi + 1]) / (hi - lo + 1)
        out[i] = values[i] - mean
    return out


def find_peaks(samples: list[Sample], signal: list[float], sample_rate_hz: float, p: Params) -> list[int]:
    """Kotlin: PpgSignalProcessor.findPeaks -- Elgendi et al. 2013 peak detection, plus an
    amplitude gate against recent real peaks (see Params.peak_min_relative_amplitude)."""
    if len(signal) < 3:
        return []

    squared_positive = [v * v if v > 0 else 0.0 for v in signal]
    peak_window_samples = max(1, round_half_up(p.elgendi_peak_window_ms / 1000.0 * sample_rate_hz))
    beat_window_samples = max(
        peak_window_samples + 1,
        round_half_up(p.elgendi_beat_window_ms / 1000.0 * sample_rate_hz),
    )

    ma_peak = moving_average(squared_positive, peak_window_samples)
    ma_beat = moving_average(squared_positive, beat_window_samples)
    alpha = p.elgendi_threshold_beta * (sum(squared_positive) / len(squared_positive))

    peaks: list[int] = []
    last_peak_ms = -(10 ** 15)
    recent_peak_level: float | None = None

    def close_block(start: int, end_inclusive: int) -> None:
        nonlocal last_peak_ms, recent_peak_level
        if end_inclusive - start + 1 < peak_window_samples:
            return
        max_idx = start
        for j in range(start, end_inclusive + 1):
            if signal[j] > signal[max_idx]:
                max_idx = j
        amplitude = signal[max_idx]
        ts = samples[max_idx].timestamp_ms
        if ts - last_peak_ms < p.peak_refractory_ms:
            return
        if recent_peak_level is not None and amplitude < p.peak_min_relative_amplitude * recent_peak_level:
            return  # too small next to recent real beats -- a dicrotic notch, not a beat
        peaks.append(ts)
        last_peak_ms = ts
        recent_peak_level = (
            amplitude if recent_peak_level is None
            else recent_peak_level + (amplitude - recent_peak_level) * p.peak_amplitude_smoothing
        )

    trailing_edge_guard = beat_window_samples // 2
    scan_limit = len(signal) - 1 - trailing_edge_guard

    block_start = -1
    for i in range(0, scan_limit + 1):
        above_threshold = ma_peak[i] > ma_beat[i] + alpha
        if above_threshold:
            if block_start == -1:
                block_start = i
        elif block_start != -1:
            close_block(block_start, i - 1)
            block_start = -1
    if block_start != -1 and scan_limit >= 0:
        close_block(block_start, scan_limit)

    return peaks


def reject_artifacts(raw_ibis: list[int], p: Params) -> list[str | None]:
    """Kotlin: PpgSignalProcessor.rejectArtifacts -- EWMA-referenced, MAD-scaled tolerance,
    with a resync after a run of consecutive rejections.

    The EWMA level only moves on an *accepted* beat. A real recording showed rejections
    clustering specifically on beats where the RR interval was lengthening (heart rate
    slowing -- the exhale half of respiratory sinus arrhythmia): the heart-rate-asymmetry
    literature confirms decelerations are typically steeper/more sustained than
    accelerations (fast vagal reactivation, slower withdrawal), so a real deceleration
    often runs for several beats. If the first beat of that run gets rejected as too big a
    jump, the level freezes there -- the next beat of the SAME real trend looks like an
    even bigger jump from the same stale reference, and so on: a self-reinforcing lockout
    that only breaks once the trend loops back near the frozen value. The exported
    recording showed exactly this signature: 2-6 consecutive rejections sharing the
    identical level, all on lengthening beats, all part of one smooth real deceleration.

    After `artifact_resync_after_rejects` consecutive rejections, resyncing the level to
    the *median* of the last `artifact_resync_window` raw (unfiltered) IBIs breaks the
    lockout: a real sustained trend has several mutually consistent raw values for the
    median to lock onto, while a single true artifact (no consistent neighbors) doesn't
    drag the median far. Verified against the recording that exposed this (13/66 rejected
    -> 8), an earlier one (6/57 -> 3), and a clean synthetic signal (unaffected -- it never
    triggers 3 consecutive rejections in the first place).
    """
    min_ibi_ms = 60_000.0 / p.max_bpm
    max_ibi_ms = 60_000.0 / p.min_bpm

    reasons: list[str | None] = [None] * len(raw_ibis)
    recent_steps: list[float] = []
    level: float | None = None
    accepted_count = 0
    consecutive_rejects = 0

    for i, ibi in enumerate(raw_ibis):
        if ibi < min_ibi_ms or ibi > max_ibi_ms:
            reasons[i] = "OUT_OF_RANGE"
            consecutive_rejects = 0
            continue

        current_level = level
        if current_level is None or accepted_count < p.artifact_baseline_beats:
            level = (current_level + (ibi - current_level) * p.artifact_level_smoothing
                     if current_level is not None else float(ibi))
            accepted_count += 1
            consecutive_rejects = 0
            continue

        step = abs(ibi - current_level)
        recent = sorted(recent_steps[-p.artifact_recent_window:])
        typical_step = recent[len(recent) // 2] if recent else 0.0
        step_mad = sorted(abs(x - typical_step) for x in recent)[len(recent) // 2] if recent else 0.0
        floor = current_level * p.artifact_tolerance_floor_fraction
        tolerance = max(floor, typical_step + step_mad * p.artifact_mad_multiplier)

        if step <= tolerance:
            recent_steps.append(step)
            level = current_level + (ibi - current_level) * p.artifact_level_smoothing
            accepted_count += 1
            consecutive_rejects = 0
        else:
            reasons[i] = "IRREGULAR"
            consecutive_rejects += 1
            if consecutive_rejects >= p.artifact_resync_after_rejects:
                window_start = max(0, i - p.artifact_resync_window + 1)
                level = statistics.median(raw_ibis[window_start:i + 1])
                recent_steps.clear()
                consecutive_rejects = 0

    return reasons


def process(samples: list[Sample], p: Params = Params()) -> ProcessingResult:
    """Kotlin: PpgSignalProcessor.process -- the full pipeline, single pass over all samples
    (this is what finishMeasurement() calls -- the FINAL saved result, not the live/tick
    reprocessing path used only for the on-screen waveform and beat log)."""
    if len(samples) < 8:
        return ProcessingResult([], [], [], [], 0, [])

    duration_ms = float(samples[-1].timestamp_ms - samples[0].timestamp_ms)
    avg_dt_ms = duration_ms / max(1, len(samples) - 1)
    sample_rate_hz = 1000.0 / avg_dt_ms if avg_dt_ms > 0 else 30.0

    detrended = detrend(samples, window_ms=800.0, sample_rate_hz=sample_rate_hz)
    smoothed = moving_average(detrended, window_samples=max(1, int(sample_rate_hz / 10)))

    beat_timestamps = find_peaks(samples, smoothed, sample_rate_hz, p)
    raw_ibis = [b - a for a, b in zip(beat_timestamps, beat_timestamps[1:])]

    reasons = reject_artifacts(raw_ibis, p)
    clean_ibis = [ibi for ibi, r in zip(raw_ibis, reasons) if r is None]
    rejected = sum(1 for r in reasons if r is not None)
    events = [IbiEvent(beat_timestamps[i + 1], raw_ibis[i], reasons[i]) for i in range(len(raw_ibis))]

    return ProcessingResult(smoothed, beat_timestamps, raw_ibis, clean_ibis, rejected, events)


def load_samples(path: str) -> list[Sample]:
    with open(path) as f:
        data = json.load(f)
    raw = data["samples"] if isinstance(data, dict) and "samples" in data else data
    return [Sample(int(s["timestampMs"]), float(s["intensity"])) for s in raw]


def summarize(result: ProcessingResult, label: str = "") -> str:
    n_ok = len(result.clean_ibi_ms)
    n_rejected = result.rejected_beat_count
    total = n_ok + n_rejected
    pct = (100.0 * n_rejected / total) if total else 0.0
    reasons = [e.rejection_reason for e in result.ibi_events if e.rejection_reason]
    oor = reasons.count("OUT_OF_RANGE")
    irr = reasons.count("IRREGULAR")
    hr = ""
    if n_ok:
        mean_ibi = statistics.mean(result.clean_ibi_ms)
        hr = f", mean HR {60000.0 / mean_ibi:.1f} bpm"
    prefix = f"[{label}] " if label else ""
    return (f"{prefix}beats detected: {len(result.beat_timestamps_ms)} -> "
            f"{n_ok} ok, {n_rejected} rejected ({pct:.0f}%) "
            f"[out_of_range={oor}, irregular={irr}]{hr}")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("samples_json", help="Path to an exported raw-sample JSON file")
    ap.add_argument("--beta", type=float, help="Elgendi threshold beta (default 0.02)")
    ap.add_argument("--min-rel-amplitude", type=float, help="Peak amplitude gate vs recent peaks (default 0.35)")
    ap.add_argument("--mad-multiplier", type=float, help="Artifact MAD multiplier (default 4.5)")
    ap.add_argument("--level-smoothing", type=float, help="Artifact EWMA smoothing factor (default 0.6)")
    ap.add_argument("--tolerance-floor-fraction", type=float, help="Artifact tolerance floor, x current level (default 0.25)")
    ap.add_argument("--recent-window", type=int, help="Artifact recent-step window (default 5)")
    ap.add_argument("--resync-after", type=int, help="Consecutive rejections before resyncing the level (default 3)")
    ap.add_argument("--resync-window", type=int, help="Raw IBIs the resync median is taken over (default 5)")
    ap.add_argument("--dump-events", action="store_true", help="Print every beat with its accept/reject outcome")
    ap.add_argument("--sweep", action="store_true", help="Grid-search common knobs and rank by rejection rate")
    args = ap.parse_args()

    samples = load_samples(args.samples_json)
    print(f"Loaded {len(samples)} samples spanning "
          f"{(samples[-1].timestamp_ms - samples[0].timestamp_ms) / 1000.0:.1f}s")

    if args.sweep:
        amps = [0.2, 0.3, 0.35, 0.45]
        mads = [3.0, 4.5, 6.0, 8.0]
        fracs = [0.15, 0.20, 0.25, 0.30]
        rows = []
        for amp, mad, frac in itertools.product(amps, mads, fracs):
            p = Params(peak_min_relative_amplitude=amp, artifact_mad_multiplier=mad, artifact_tolerance_floor_fraction=frac)
            r = process(samples, p)
            total = len(r.clean_ibi_ms) + r.rejected_beat_count
            pct = (100.0 * r.rejected_beat_count / total) if total else 100.0
            rows.append((pct, amp, mad, frac, len(r.clean_ibi_ms), r.rejected_beat_count))
        rows.sort(key=lambda r: r[0])
        print(f"\n{'reject%':>8} {'minAmp':>7} {'mad':>5} {'floorFrac':>9} {'ok':>4} {'rej':>4}")
        for pct, amp, mad, frac, ok, rej in rows[:20]:
            print(f"{pct:7.1f}% {amp:7.2f} {mad:5.1f} {frac:9.2f} {ok:4d} {rej:4d}")
        return

    p = Params()
    if args.beta is not None:
        p.elgendi_threshold_beta = args.beta
    if args.min_rel_amplitude is not None:
        p.peak_min_relative_amplitude = args.min_rel_amplitude
    if args.mad_multiplier is not None:
        p.artifact_mad_multiplier = args.mad_multiplier
    if args.level_smoothing is not None:
        p.artifact_level_smoothing = args.level_smoothing
    if args.tolerance_floor_fraction is not None:
        p.artifact_tolerance_floor_fraction = args.tolerance_floor_fraction
    if args.recent_window is not None:
        p.artifact_recent_window = args.recent_window
    if args.resync_after is not None:
        p.artifact_resync_after_rejects = args.resync_after
    if args.resync_window is not None:
        p.artifact_resync_window = args.resync_window

    result = process(samples, p)
    print(summarize(result))

    if args.dump_events:
        print()
        for e in result.ibi_events:
            status = "OK" if e.accepted else e.rejection_reason
            print(f"  t={e.at_ms:>7}ms  ibi={e.ibi_ms:>5}ms  {status}")


if __name__ == "__main__":
    main()
