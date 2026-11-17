#!/usr/bin/env python3
"""
Fetches and prepares genuine offline Hindi neural TTS runtime (Sherpa-ONNX static AAR)
and model assets (Piper hi_IN int8 + Kokoro multi-lang int8 + shared espeak-ng-data)
into app/libs and app/build/generated/tts-assets. Idempotent and safe for Gradle preBuild.
"""
import io
import json
import os
import pathlib
import sys
import tarfile
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
LIBS_DIR = ROOT / "app" / "libs"
ASSETS_OUT = ROOT / "app" / "build" / "generated" / "tts-assets" / "voices"
CACHE_DIR = ROOT / "app" / "build" / "tmp" / "tts-downloads"

AAR_NAME = "sherpa-onnx-static-link-onnxruntime-1.13.8.aar"
AAR_URL = f"https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/{AAR_NAME}"

PIPER_MODELS = [
    (
        "priyamvada",
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-hi_IN-priyamvada-medium-int8.tar.bz2",
        "hi_IN-priyamvada-medium",
    ),
    (
        "pratham",
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-hi_IN-pratham-medium-int8.tar.bz2",
        "hi_IN-pratham-medium",
    ),
    (
        "rohan",
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-hi_IN-rohan-medium-int8.tar.bz2",
        "hi_IN-rohan-medium",
    ),
]

KOKORO_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_0.tar.bz2"
SUPERTONIC_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2"
SUPERTONIC_FILES = (
    "duration_predictor.int8.onnx",
    "text_encoder.int8.onnx",
    "vector_estimator.int8.onnx",
    "vocoder.int8.onnx",
    "tts.json",
    "unicode_indexer.bin",
    "voice.bin",
    "LICENSE",
)
STAMP_FILE = ASSETS_OUT / ".tts-assets-v2.ready"


def download(url: str, dest: pathlib.Path) -> pathlib.Path:
    dest.parent.mkdir(parents=True, exist_ok=True)
    if dest.exists() and dest.stat().st_size > 1024:
        return dest
    tmp = dest.with_suffix(dest.suffix + ".part")
    print(f"Downloading {url} -> {dest.name}...", flush=True)
    urllib.request.urlretrieve(url, tmp)
    tmp.replace(dest)
    return dest


def extract_espeak_once(tf: tarfile.TarFile, dest_espeak: pathlib.Path) -> None:
    if (dest_espeak / "phontab").exists() and (dest_espeak / "hi_dict").exists():
        return
    dest_espeak.mkdir(parents=True, exist_ok=True)
    marker = "/espeak-ng-data/"
    for member in tf.getmembers():
        if not member.isfile() or marker not in member.name:
            continue
        rel = member.name.split(marker, 1)[1]
        if not rel or rel.startswith("/") or ".." in pathlib.PurePosixPath(rel).parts:
            continue
        target = dest_espeak / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        src = tf.extractfile(member)
        if src is not None:
            with src, open(target, "wb") as out:
                out.write(src.read())


def extract_piper(name: str, url: str, model_stem: str) -> None:
    archive = download(url, CACHE_DIR / f"{name}.tar.bz2")
    out_dir = ASSETS_OUT / "piper" / name
    model_file = out_dir / f"{model_stem}.onnx"
    tokens_file = out_dir / "tokens.txt"
    config_file = out_dir / f"{model_stem}.onnx.json"
    espeak_dir = ASSETS_OUT / "espeak-ng-data"

    if model_file.exists() and tokens_file.exists() and config_file.exists() and (espeak_dir / "hi_dict").exists():
        return

    out_dir.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive, "r:bz2") as tf:
        extract_espeak_once(tf, espeak_dir)
        for member in tf.getmembers():
            if not member.isfile():
                continue
            base = pathlib.PurePosixPath(member.name).name
            if base in (f"{model_stem}.onnx", f"{model_stem}.onnx.json", "tokens.txt", "MODEL_CARD"):
                src = tf.extractfile(member)
                if src is not None:
                    with src, open(out_dir / base, "wb") as out:
                        out.write(src.read())


def extract_kokoro() -> None:
    archive = download(KOKORO_URL, CACHE_DIR / "kokoro-int8-multi-lang-v1_0.tar.bz2")
    out_dir = ASSETS_OUT / "kokoro"
    model_file = out_dir / "model.int8.onnx"
    voices_file = out_dir / "voices.bin"
    tokens_file = out_dir / "tokens.txt"
    espeak_dir = ASSETS_OUT / "espeak-ng-data"

    if model_file.exists() and voices_file.exists() and tokens_file.exists() and (espeak_dir / "hi_dict").exists():
        return

    out_dir.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive, "r:bz2") as tf:
        extract_espeak_once(tf, espeak_dir)
        for member in tf.getmembers():
            if not member.isfile():
                continue
            if "/dict/" in member.name or "/espeak-ng-data/" in member.name:
                continue
            base = pathlib.PurePosixPath(member.name).name
            if base in ("model.int8.onnx", "voices.bin", "tokens.txt", "LICENSE"):
                src = tf.extractfile(member)
                if src is not None:
                    with src, open(out_dir / base, "wb") as out:
                        out.write(src.read())


def extract_supertonic() -> None:
    archive = download(SUPERTONIC_URL, CACHE_DIR / "supertonic-3.tar.bz2")
    out_dir = ASSETS_OUT / "supertonic"
    required = [out_dir / fname for fname in SUPERTONIC_FILES[:-1]]
    if all(p.exists() and p.stat().st_size > 0 for p in required):
        return

    out_dir.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive, "r:bz2") as tf:
        for member in tf.getmembers():
            if not member.isfile():
                continue
            base = pathlib.PurePosixPath(member.name).name
            if base in SUPERTONIC_FILES:
                src = tf.extractfile(member)
                if src is not None:
                    with src, open(out_dir / base, "wb") as out:
                        out.write(src.read())


def main() -> int:
    # Ensure AAR exists
    download(AAR_URL, LIBS_DIR / AAR_NAME)

    expected = [
        ASSETS_OUT / "espeak-ng-data" / "hi_dict",
        ASSETS_OUT / "piper" / "priyamvada" / "hi_IN-priyamvada-medium.onnx",
        ASSETS_OUT / "piper" / "pratham" / "hi_IN-pratham-medium.onnx",
        ASSETS_OUT / "piper" / "rohan" / "hi_IN-rohan-medium.onnx",
        ASSETS_OUT / "kokoro" / "model.int8.onnx",
        ASSETS_OUT / "kokoro" / "voices.bin",
        ASSETS_OUT / "kokoro" / "tokens.txt",
        *(ASSETS_OUT / "supertonic" / fname for fname in SUPERTONIC_FILES[:-1]),
    ]
    if STAMP_FILE.exists() and all(p.exists() for p in expected):
        return 0

    ASSETS_OUT.mkdir(parents=True, exist_ok=True)
    for name, url, stem in PIPER_MODELS:
        extract_piper(name, url, stem)
    extract_kokoro()
    extract_supertonic()

    # Record clean asset manifest for diagnostics/verification
    manifest = {
        "espeak_files": sum(1 for _ in (ASSETS_OUT / "espeak-ng-data").rglob("*") if _.is_file()),
        "piper": [name for name, _, _ in PIPER_MODELS],
        "kokoro_speakers": {"hf_alpha": 31, "hf_beta": 32, "hm_omega": 33, "hm_psi": 34},
        "supertonic_speakers": {"F1": 0, "F2": 1, "M1": 5},
    }
    STAMP_FILE.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(f"Prepared offline Hindi TTS assets at {ASSETS_OUT}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
