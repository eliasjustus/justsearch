"""Extract OHR-Bench single-page PDFs through Docling and save as .txt files.

Creates a jseval-compatible corpus at tmp/eval-corpora/mixed/ohr-bench-docling/
with one .txt file per PDF, matching the filenames used by ohr-bench-tika-pdf.

Usage:
    python scripts/search/extract-ohrbench-docling.py [--max N] [--gpu]
"""

import argparse
import sys
import time
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input-dir", type=Path,
                        default=Path("tmp/eval-corpora/mixed/ohr-bench-tika-pdf"),
                        help="Directory with single-page PDFs")
    parser.add_argument("--output-dir", type=Path,
                        default=Path("tmp/eval-corpora/mixed/ohr-bench-docling"),
                        help="Output directory for extracted .txt files")
    parser.add_argument("--max", type=int, default=0,
                        help="Max PDFs to process (0 = all)")
    parser.add_argument("--gpu", action="store_true",
                        help="Use GPU acceleration for layout detection")
    args = parser.parse_args()

    from docling.document_converter import DocumentConverter, PdfFormatOption
    from docling.datamodel.pipeline_options import PdfPipelineOptions
    from docling.datamodel.base_models import InputFormat

    # Configure Docling pipeline
    pipeline_options = PdfPipelineOptions()
    pipeline_options.do_ocr = True
    pipeline_options.do_table_structure = True
    if args.gpu:
        from docling.datamodel.pipeline_options import AcceleratorDevice
        pipeline_options.accelerator_device = AcceleratorDevice.CUDA

    converter = DocumentConverter(
        format_options={
            InputFormat.PDF: PdfFormatOption(pipeline_options=pipeline_options),
        }
    )

    args.output_dir.mkdir(parents=True, exist_ok=True)

    pdfs = sorted(args.input_dir.glob("*.pdf"))
    if args.max > 0:
        pdfs = pdfs[:args.max]

    total = len(pdfs)
    print(f"Extracting {total} PDFs through Docling...")
    print(f"  Input:  {args.input_dir}")
    print(f"  Output: {args.output_dir}")
    print(f"  GPU:    {args.gpu}")

    start = time.time()
    success = 0
    errors = 0

    for i, pdf_path in enumerate(pdfs):
        txt_name = pdf_path.stem + ".txt"
        txt_path = args.output_dir / txt_name

        if txt_path.exists():
            success += 1
            if (i + 1) % 100 == 0:
                elapsed = time.time() - start
                rate = (i + 1) / elapsed
                print(f"  [{i+1}/{total}] {rate:.1f} docs/s (skipped existing)")
            continue

        try:
            result = converter.convert(str(pdf_path))
            text = result.document.export_to_text()
            txt_path.write_text(text, encoding="utf-8")
            success += 1
        except Exception as e:
            print(f"  ERROR: {pdf_path.name}: {e}", file=sys.stderr)
            # Write empty file so jseval can still index it
            txt_path.write_text("", encoding="utf-8")
            errors += 1

        if (i + 1) % 50 == 0:
            elapsed = time.time() - start
            rate = (i + 1) / elapsed
            eta = (total - i - 1) / rate if rate > 0 else 0
            print(f"  [{i+1}/{total}] {rate:.1f} docs/s, ETA {eta:.0f}s, errors={errors}")

    elapsed = time.time() - start
    print(f"\nDone: {success} extracted, {errors} errors in {elapsed:.1f}s ({total/elapsed:.1f} docs/s)")

    # Write sentinel for jseval watcher
    sentinel = args.output_dir / "__jseval_sentinel__.txt"
    if not sentinel.exists():
        sentinel.write_text("sentinel", encoding="utf-8")


if __name__ == "__main__":
    main()
