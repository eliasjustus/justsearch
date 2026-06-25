"""
VLM extraction for the 50-page evaluation sample.

Reads the manifest from vlm-eval-sample.json, extracts text from each PDF
via llama-server vision endpoint, saves to an output directory.

Usage:
    python scripts/search/vlm-extract-sample.py --output tmp/eval-corpora/mixed/ohr-bench-vlm-run0 [options]
"""

import argparse
import base64
import json
import logging
import sys
import time
import urllib.request
from pathlib import Path

import fitz  # PyMuPDF

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    handlers=[logging.StreamHandler(sys.stdout)],
)
log = logging.getLogger(__name__)

# Default prompt (Run 0 — current VDU prompt style)
PROMPTS = {
    "markdown": (
        "Extract all text from this document image. Include:\n"
        "- Headings and paragraphs\n"
        "- Table data (format as markdown tables)\n"
        "- Form field labels and values\n"
        "- Any visible text, numbers, or dates\n\n"
        "Output the extracted content in markdown format."
    ),
    "plain": (
        "Return the plain text representation of this document "
        "as if you were reading it naturally. Do not add any commentary."
    ),
    "olmocr": (
        "Just return the plain text representation of this document "
        "as if you were reading it naturally. Convert equations to LaTeX "
        "and tables to markdown.\nDo not add any commentary or preamble."
    ),
    "anchored": None,  # Built dynamically with Tika text
}


def render_pdf_page(pdf_path: Path, dpi: int = 150, fmt: str = "png") -> bytes:
    doc = fitz.open(str(pdf_path))
    page = doc[0]
    pix = page.get_pixmap(dpi=dpi)
    img_bytes = pix.tobytes(fmt)
    doc.close()
    return img_bytes


def extract_pdf_text(pdf_path: Path) -> str:
    """Extract text layer from PDF using PyMuPDF (for document anchoring)."""
    doc = fitz.open(str(pdf_path))
    text = doc[0].get_text()
    doc.close()
    return text.strip()


def sanitize_text(text: str) -> str:
    """Remove characters that break JSON or confuse the VLM."""
    # Remove null bytes, control chars (keep newline/tab), surrogates
    out = []
    for c in text:
        cp = ord(c)
        if cp == 0:
            continue
        if cp < 32 and c not in "\n\r\t":
            continue
        if 0xD800 <= cp <= 0xDFFF:
            continue
        out.append(c)
    return "".join(out)


def build_anchored_prompt(tika_text: str, max_chars: int = 800) -> str:
    """Build an anchored prompt with sampled text-layer snippets.

    olmOCR style: sample start + end of the text layer (not full dump).
    Framed as noisy hints, not authoritative reference. Small budget
    to avoid overflowing the 4096-token context with CJK text.
    """
    clean = sanitize_text(tika_text.strip())
    if not clean:
        # No text layer — fall back to plain prompt
        return (
            "Return the plain text representation of this document "
            "as if you were reading it naturally. Do not add any commentary."
        )

    # Sample: first half + last half of budget
    half = max_chars // 2
    if len(clean) <= max_chars:
        snippet = clean
    else:
        snippet = clean[:half] + "\n[...]\n" + clean[-half:]

    return (
        "Return the plain text representation of this document page "
        "as if you were reading it naturally.\n\n"
        "For reference, here is noisy text from the PDF's text layer "
        "(it may contain errors — trust the image over this text):\n"
        f'"""\n{snippet}\n"""\n\n'
        "Do not add any commentary."
    )


def vlm_extract(img_bytes: bytes, prompt: str, server_url: str,
                temperature: float = 0.1, max_tokens: int = 4096,
                img_format: str = "png") -> tuple[str, float]:
    mime = "image/png" if img_format == "png" else "image/jpeg"
    b64 = base64.b64encode(img_bytes).decode("utf-8")
    payload = {
        "model": "qwen3.5",
        "messages": [{
            "role": "user",
            "content": [
                {"type": "text", "text": prompt},
                {"type": "image_url", "image_url": {"url": f"data:{mime};base64,{b64}"}},
            ],
        }],
        "max_tokens": max_tokens,
        "temperature": temperature,
        "top_p": 0.9,
        "chat_template_kwargs": {"enable_thinking": False},
    }

    start = time.time()
    last_err = None
    for attempt in range(3):
        try:
            req = urllib.request.Request(
                f"{server_url}/v1/chat/completions",
                data=json.dumps(payload).encode("utf-8"),
                headers={"Content-Type": "application/json"},
            )
            resp = urllib.request.urlopen(req, timeout=600)
            result = json.loads(resp.read())
            elapsed = time.time() - start
            text = result["choices"][0]["message"]["content"]
            if "<think>" in text:
                import re
                text = re.sub(r"<think>.*?</think>", "", text, flags=re.DOTALL).strip()
            return text, elapsed
        except urllib.error.HTTPError as e:
            last_err = e
            log.warning(f"  HTTP {e.code} attempt {attempt+1}")
            if attempt < 2:
                time.sleep(2 * (attempt + 1))
    raise last_err


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--port", type=int, default=8090)
    parser.add_argument("--prompt", choices=list(PROMPTS.keys()), default="markdown")
    parser.add_argument("--dpi", type=int, default=150)
    parser.add_argument("--temperature", type=float, default=0.1)
    parser.add_argument("--max-tokens", type=int, default=4096)
    parser.add_argument("--img-format", choices=["png", "jpeg"], default="png")
    args = parser.parse_args()

    server_url = f"http://127.0.0.1:{args.port}"
    manifest = json.loads(Path("tmp/eval-corpora/vlm-eval-sample.json").read_text(encoding="utf-8"))
    pdf_dir = Path("tmp/eval-corpora/mixed/ohr-bench-tika-pdf")

    args.output.mkdir(parents=True, exist_ok=True)

    log.info(f"Extracting {len(manifest)} pages | prompt={args.prompt} dpi={args.dpi} temp={args.temperature} fmt={args.img_format}")

    ok = 0
    errors = 0
    total_time = 0

    for i, page in enumerate(manifest, 1):
        pdf_path = pdf_dir / (page["encoded"] + ".pdf")
        txt_path = args.output / (page["encoded"] + ".txt")

        if txt_path.exists() and txt_path.stat().st_size > 0:
            log.info(f"[{i}/{len(manifest)}] skip {page['domain']}/{page['did'][-30:]}")
            ok += 1
            continue

        try:
            img_bytes = render_pdf_page(pdf_path, dpi=args.dpi, fmt=args.img_format)

            if args.prompt == "anchored":
                tika_text = extract_pdf_text(pdf_path)
                prompt = build_anchored_prompt(tika_text)
            else:
                prompt = PROMPTS[args.prompt]

            text, elapsed = vlm_extract(
                img_bytes, prompt, server_url,
                temperature=args.temperature,
                max_tokens=args.max_tokens,
                img_format=args.img_format,
            )
            txt_path.write_text(text, encoding="utf-8")
            total_time += elapsed
            ok += 1

            bracket = "HARD" if page["docling_overlap"] < 0.5 else ("MED" if page["docling_overlap"] < 0.85 else "EASY")
            log.info(f"[{i}/{len(manifest)}] [{bracket:4s}] {elapsed:5.1f}s {len(text):5d}ch {page['domain']}/{page['did'][-30:]}")

        except Exception as e:
            errors += 1
            log.error(f"[{i}/{len(manifest)}] FAIL: {e}")

    avg = total_time / max(ok, 1)
    log.info(f"Done: {ok} ok, {errors} errors, avg {avg:.1f}s/page, total {total_time/60:.1f}min")

    # Save run metadata
    meta = {
        "prompt": args.prompt,
        "dpi": args.dpi,
        "temperature": args.temperature,
        "max_tokens": args.max_tokens,
        "img_format": args.img_format,
        "pages": len(manifest),
        "ok": ok,
        "errors": errors,
        "avg_seconds_per_page": round(avg, 1),
        "total_minutes": round(total_time / 60, 1),
    }
    (args.output / "_run_meta.json").write_text(json.dumps(meta, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
