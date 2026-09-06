"""Fail-closed materialization of the raw format-breadth-v1 sibling corpus.

Only the recipe and observed manifest are repository artifacts.  Upstream bytes and the
materialized ``datasets/mixed/format-breadth-v1`` tree remain in the shared fetch cache and
gitignored datasets tree respectively.
"""

from __future__ import annotations

import contextlib
from dataclasses import dataclass
from email.parser import BytesHeaderParser
from email.policy import compat32
from email.utils import getaddresses
import hashlib
import json
import re
import shutil
import tarfile
import tempfile
import unicodedata
from pathlib import Path, PurePosixPath
from typing import Any, Iterator
from urllib.parse import urlparse
from urllib.request import Request, urlopen
import zipfile

from . import dataset_cache
from . import raw_corpus_manifest


SCHEMA_RECIPE = "jseval.format-breadth-recipe.v1"
SCHEMA_MANIFEST = "jseval.format-breadth-manifest.v1"
DATASET = "mixed/format-breadth-v1"
ENRON_COUNT = 16
GOVDOCS_RTF_COUNT = 9
NAPIER_ZIP_COUNT = 8
_USER_AGENT = "justsearch-jseval/format-breadth-v1 (tempdoc-897)"
_HEX_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_EXPECTED_COVERAGE_QUALIFICATION = {
    extension: {
        "sample_count": count,
        "source_collection_count": 1,
        "producer_count": 1,
        "producer_count_basis": (
            "conservative collection-level proxy; producer diversity not established"
        ),
        "claim": "single-source deterministic real-input characterization",
    }
    for extension, count in {
        ".eml": ENRON_COUNT,
        ".rtf": GOVDOCS_RTF_COUNT,
        ".zip": NAPIER_ZIP_COUNT,
    }.items()
}
_UNOBSERVED_MANIFEST = {
    "schema": SCHEMA_MANIFEST,
    "status": "unobserved",
    "dataset": DATASET,
    "note": (
        "Run corpus-fetch-format-breadth --write-manifest once, review all observed source "
        "and member hashes, then commit the resulting manifest. Ordinary materialization "
        "fails closed until this is observed."
    ),
}


class FormatBreadthError(ValueError):
    """The recipe, upstream source, manifest, or output violates the frozen contract."""


def _sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _canonical_json_sha(value: Any) -> str:
    encoded = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return _sha256_bytes(encoded.encode("utf-8"))


def _load_json_object(path: Path, *, label: str) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise FormatBreadthError(f"cannot read {label}: {exc}") from exc
    if not isinstance(value, dict):
        raise FormatBreadthError(f"{label} must be a JSON object")
    return value


def _require_sha256(value: object, *, label: str) -> str:
    if not isinstance(value, str) or not _HEX_SHA256.fullmatch(value):
        raise FormatBreadthError(f"{label} must be a lowercase SHA-256")
    return value


def _safe_member_name(name: str, *, label: str) -> str:
    if not isinstance(name, str) or not name or "\\" in name or "\x00" in name:
        raise FormatBreadthError(f"{label} is not a canonical safe POSIX path: {name!r}")
    if unicodedata.normalize("NFC", name) != name:
        raise FormatBreadthError(f"{label} is not NFC: {name!r}")
    path = PurePosixPath(name)
    parts = name.split("/")
    if path.is_absolute() or any(part in ("", ".", "..") for part in parts):
        raise FormatBreadthError(f"{label} is not a canonical safe POSIX path: {name!r}")
    if ":" in parts[0] or path.as_posix() != name:
        raise FormatBreadthError(f"{label} is not a canonical safe POSIX path: {name!r}")
    try:
        name.encode("utf-8")
    except UnicodeEncodeError as exc:
        raise FormatBreadthError(f"{label} is not valid UTF-8: {name!r}") from exc
    return name


def _validate_unique_names(names: Iterator[str], *, label: str) -> list[str]:
    result: list[str] = []
    seen: set[str] = set()
    for raw_name in names:
        name = _safe_member_name(raw_name, label=label)
        if name in seen:
            raise FormatBreadthError(f"{label} contains duplicate member path {name!r}")
        seen.add(name)
        result.append(name)
    return result


def _download(url: str, destination: Path) -> None:
    parsed = urlparse(url)
    if parsed.scheme != "https" or not parsed.netloc:
        raise FormatBreadthError(f"upstream URL must be absolute HTTPS: {url!r}")
    request = Request(url, headers={"User-Agent": _USER_AGENT})
    with urlopen(request, timeout=None) as response, destination.open("wb") as output:  # noqa: S310
        shutil.copyfileobj(response, output, length=1024 * 1024)


def _source(recipe: dict[str, Any], name: str) -> dict[str, Any]:
    sources = recipe.get("sources")
    if not isinstance(sources, dict) or not isinstance(sources.get(name), dict):
        raise FormatBreadthError(f"recipe.sources.{name} must be an object")
    return sources[name]


def _populate_source(destination: Path, source: dict[str, Any], filenames: list[str]) -> None:
    urls = source.get("urls")
    if not isinstance(urls, list) or len(urls) != len(filenames):
        raise FormatBreadthError("source urls and cache filenames must have equal nonzero length")
    for url, filename in zip(urls, filenames, strict=True):
        if not isinstance(url, str):
            raise FormatBreadthError("source URL must be a string")
        _download(url, destination / filename)


@contextlib.contextmanager
def _cached_source(
    fetcher: str, source: dict[str, Any], filenames: list[str],
) -> Iterator[Path]:
    urls = source.get("urls")
    cache_fetcher = source.get("cache_fetcher", fetcher)
    cache_params = source.get("cache_params")
    if not isinstance(cache_fetcher, str) or not cache_fetcher:
        raise FormatBreadthError("source.cache_fetcher must be a nonempty string when present")
    if cache_params is not None and not isinstance(cache_params, dict):
        raise FormatBreadthError("source.cache_params must be an object when present")
    params = cache_params or {"urls": urls, "source_revision": source.get("source_revision")}
    with dataset_cache.cached_dir(
        cache_fetcher, params, filenames=filenames,
        populate=lambda destination: _populate_source(destination, source, filenames),
    ) as directory:
        yield directory


def _verify_source_hashes(
    source: dict[str, Any], directory: Path, filenames: list[str], *,
    observed: dict[str, str] | None,
) -> dict[str, str]:
    pinned = source.get("sha256")
    if not isinstance(pinned, dict):
        raise FormatBreadthError("source.sha256 must be an object")
    actual: dict[str, str] = {}
    for filename in filenames:
        path = directory / filename
        if not path.is_file():
            raise FormatBreadthError(f"cached source is missing {filename}")
        digest = _sha256_file(path)
        expected = pinned.get(filename)
        if expected is not None and digest != _require_sha256(expected, label=f"pinned {filename}"):
            raise FormatBreadthError(f"pinned source hash drift for {filename}: {digest}")
        if observed is not None:
            observed_digest = _require_sha256(observed.get(filename), label=f"observed {filename}")
            if digest != observed_digest:
                raise FormatBreadthError(f"observed source hash drift for {filename}: {digest}")
        actual[filename] = digest
    return actual


def _sender_domain(raw: bytes) -> str | None:
    header, _, _body = raw.partition(b"\n\n")
    if not _body:
        header, _, _body = raw.partition(b"\r\n\r\n")
    try:
        message = BytesHeaderParser(policy=compat32).parsebytes(header + b"\n\n")
    except (TypeError, ValueError):
        return None
    addresses = getaddresses(message.get_all("from", []))
    domains = sorted({address.rsplit("@", 1)[1].lower().rstrip(".")
                      for _name, address in addresses if "@" in address})
    return domains[0] if len(domains) == 1 and domains[0] else None


@dataclass(frozen=True)
class _EmailCandidate:
    rank: str
    member: str
    sender_domain: str | None
    raw: bytes


def _rank(seed: int, member: str) -> str:
    return _sha256_bytes(f"{seed}\0{member}".encode("utf-8"))


def _select_enron(path: Path, *, seed: int) -> list[_EmailCandidate]:
    # Both pools are capped at the requested output size.  Keeping one candidate for every
    # observed domain would make memory depend on upstream cardinality; the top-16 domain
    # winners can instead be maintained online without changing the final rank order.
    best_by_domain: dict[str, _EmailCandidate] = {}
    all_candidates: list[_EmailCandidate] = []
    names: set[str] = set()
    with tarfile.open(path, "r:gz") as archive:
        for member in archive:
            if not member.isfile():
                continue
            safe_name = _safe_member_name(member.name, label="Enron archive member")
            if safe_name in names:
                raise FormatBreadthError(
                    f"Enron archive contains duplicate member path {safe_name!r}"
                )
            names.add(safe_name)
            extracted = archive.extractfile(member)
            if extracted is None:
                raise FormatBreadthError(f"cannot extract Enron member {safe_name!r}")
            raw = extracted.read()
            candidate = _EmailCandidate(_rank(seed, safe_name), safe_name, _sender_domain(raw), raw)
            if len(all_candidates) < ENRON_COUNT:
                all_candidates.append(candidate)
            else:
                worst_index = max(range(len(all_candidates)), key=lambda i: all_candidates[i].rank)
                if candidate.rank < all_candidates[worst_index].rank:
                    all_candidates[worst_index] = candidate
            if candidate.sender_domain is not None:
                previous = best_by_domain.get(candidate.sender_domain)
                if previous is None or candidate.rank < previous.rank:
                    if previous is not None:
                        best_by_domain[candidate.sender_domain] = candidate
                    elif len(best_by_domain) < ENRON_COUNT:
                        best_by_domain[candidate.sender_domain] = candidate
                    else:
                        worst_domain, worst = max(
                            best_by_domain.items(), key=lambda item: item[1].rank
                        )
                        if candidate.rank < worst.rank:
                            del best_by_domain[worst_domain]
                            best_by_domain[candidate.sender_domain] = candidate
    domain_winners = sorted(best_by_domain.values(), key=lambda value: (value.rank, value.member))
    chosen = domain_winners[:ENRON_COUNT]
    chosen_names = {candidate.member for candidate in chosen}
    if len(chosen) < ENRON_COUNT:
        for candidate in sorted(all_candidates, key=lambda value: (value.rank, value.member)):
            if candidate.member not in chosen_names:
                chosen.append(candidate)
                chosen_names.add(candidate.member)
            if len(chosen) == ENRON_COUNT:
                break
    if len(chosen) != ENRON_COUNT:
        raise FormatBreadthError(
            f"Enron source has only {len(chosen)} selectable messages; expected {ENRON_COUNT}"
        )
    return sorted(chosen, key=lambda value: (value.sender_domain or "~", value.member))


def _zip_file_infos(archive: zipfile.ZipFile, *, label: str) -> list[zipfile.ZipInfo]:
    infos = [info for info in archive.infolist() if not info.is_dir()]
    _validate_unique_names((info.filename for info in infos), label=label)
    return infos


def _select_govdocs(paths: list[Path]) -> list[tuple[str, str, bytes]]:
    selected: list[tuple[str, str, bytes]] = []
    for path in paths:
        with zipfile.ZipFile(path) as archive:
            infos = _zip_file_infos(archive, label=f"Govdocs {path.name}")
            for info in infos:
                if PurePosixPath(info.filename).suffix.lower() == ".rtf":
                    selected.append((path.name, info.filename, archive.read(info)))
    selected.sort(key=lambda value: (value[0], value[1]))
    if len(selected) != GOVDOCS_RTF_COUNT:
        raise FormatBreadthError(
            f"Govdocs sources contain {len(selected)} RTF members; expected {GOVDOCS_RTF_COUNT}"
        )
    return selected


def _embedded_zip_members(raw: bytes, *, outer_member: str) -> list[dict[str, Any]]:
    try:
        from io import BytesIO
        with zipfile.ZipFile(BytesIO(raw)) as archive:
            infos = _zip_file_infos(archive, label=f"Napier inner ZIP {outer_member}")
            return [
                {"path": info.filename, "sha256": _sha256_bytes(archive.read(info))}
                for info in sorted(infos, key=lambda value: value.filename)
            ]
    except zipfile.BadZipFile as exc:
        raise FormatBreadthError(f"Napier selected member is not a valid ZIP: {outer_member}") from exc


def _sidecar_archive_sha(raw: bytes, *, archive_filename: str) -> str:
    try:
        text = raw.decode("utf-8-sig")
    except UnicodeDecodeError as exc:
        raise FormatBreadthError("Napier hash sidecar is not UTF-8") from exc
    matches: set[str] = set()
    for line in text.splitlines():
        match = re.fullmatch(
            r"\s*SHA256\s+32\s+([0-9a-fA-F]{64})\s*",
            line,
        )
        if match:
            matches.add(match.group(1).lower())
    if len(matches) != 1:
        raise FormatBreadthError(
            f"Napier sidecar for {archive_filename} must declare exactly one SHA256 row; "
            f"got {len(matches)}"
        )
    return next(iter(matches))


def _select_napier(path: Path) -> list[tuple[str, bytes, list[dict[str, Any]]]]:
    with zipfile.ZipFile(path) as archive:
        infos = _zip_file_infos(archive, label="Napier outer ZIP")
        candidates = sorted(
            (info for info in infos if PurePosixPath(info.filename).suffix.lower() == ".zip"),
            key=lambda value: value.filename,
        )
        if len(candidates) < NAPIER_ZIP_COUNT:
            raise FormatBreadthError(
                f"Napier source has {len(candidates)} inner ZIP members; expected at least {NAPIER_ZIP_COUNT}"
            )
        result = []
        for info in candidates[:NAPIER_ZIP_COUNT]:
            raw = archive.read(info)
            result.append((info.filename, raw, _embedded_zip_members(raw, outer_member=info.filename)))
        return result


def _expected_manifest_rows(manifest: dict[str, Any], source: str) -> list[dict[str, Any]]:
    sources = manifest.get("sources")
    if not isinstance(sources, dict) or not isinstance(sources.get(source), dict):
        raise FormatBreadthError(f"manifest.sources.{source} must be an object")
    rows = sources[source].get("members")
    if not isinstance(rows, list) or not all(isinstance(row, dict) for row in rows):
        raise FormatBreadthError(f"manifest.sources.{source}.members must be an array of objects")
    return rows


def _assert_manifest_matches(actual: dict[str, Any], expected: dict[str, Any]) -> None:
    if _canonical_json_sha(actual) != _canonical_json_sha(expected):
        raise FormatBreadthError("observed source/member manifest drift; rerun with reviewed upstream pins")


def _realdocs_hashes(
    realdocs_dir: Path, realdocs_manifest_path: Path,
) -> tuple[set[str], str]:
    declared = _load_json_object(realdocs_manifest_path, label="realdocs-v1 manifest")
    if declared.get("dataset") != "mixed/realdocs-v1":
        raise FormatBreadthError("realdocs-v1 manifest dataset mismatch")
    try:
        projected = raw_corpus_manifest.project_legacy_raw_manifest(declared)
        identity = raw_corpus_manifest.validate_raw_manifest(realdocs_dir, projected)
    except raw_corpus_manifest.RawCorpusManifestError as exc:
        raise FormatBreadthError(
            f"materialized realdocs-v1 does not match its immutable manifest: {exc}"
        ) from exc
    return (
        {row["sha256"] for row in identity.manifest["files"]},
        identity.digest,
    )


def _write_dataset(dataset_dir: Path, files: list[tuple[str, bytes]], metadata: dict[str, Any]) -> None:
    with tempfile.TemporaryDirectory(prefix="format-breadth-v1-", dir=dataset_dir.parent) as temporary:
        staged = Path(temporary)
        corpus_dir = staged / "corpus-dir"
        corpus_dir.mkdir(parents=True)
        for relative, raw in files:
            (corpus_dir / relative).write_bytes(raw)
        (staged / "metadata.json").write_text(
            json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n"
        )
        if dataset_dir.exists():
            current = sorted((p.relative_to(dataset_dir).as_posix(), _sha256_file(p))
                             for p in dataset_dir.rglob("*") if p.is_file())
            proposed = sorted((p.relative_to(staged).as_posix(), _sha256_file(p))
                              for p in staged.rglob("*") if p.is_file())
            if current == proposed:
                return
            raise FormatBreadthError(
                f"output dataset already exists with different bytes: {dataset_dir}"
            )
        staged.rename(dataset_dir)


def materialize_format_breadth(
    *, dataset_dir: Path, realdocs_dir: Path, realdocs_manifest_path: Path,
    recipe_path: Path, manifest_path: Path,
    write_manifest: bool = False,
) -> dict[str, Any]:
    """Verify all sources and materialize the frozen 16 EML + 9 RTF + 8 ZIP corpus."""
    recipe = _load_json_object(recipe_path, label="format-breadth recipe")
    if recipe.get("schema") != SCHEMA_RECIPE or recipe.get("dataset") != DATASET:
        raise FormatBreadthError("recipe schema/dataset mismatch")
    seed = recipe.get("enron_selection_seed")
    if not isinstance(seed, int) or isinstance(seed, bool) or seed < 0:
        raise FormatBreadthError("recipe.enron_selection_seed must be a nonnegative integer")
    expected_recipe_counts = {".eml": ENRON_COUNT, ".rtf": GOVDOCS_RTF_COUNT,
                              ".zip": NAPIER_ZIP_COUNT}
    if recipe.get("output_counts") != expected_recipe_counts:
        raise FormatBreadthError("recipe.output_counts does not match the frozen 16/9/8 contract")
    if recipe.get("coverage_qualification") != _EXPECTED_COVERAGE_QUALIFICATION:
        raise FormatBreadthError(
            "recipe.coverage_qualification must report one conservative producer/source "
            "collection per covered format"
        )
    if recipe.get("enron_selection_method") != "sha256-ranked-one-per-sender-domain-then-fill-v1":
        raise FormatBreadthError("unsupported recipe.enron_selection_method")
    manifest = _load_json_object(manifest_path, label="format-breadth manifest")
    if manifest.get("schema") != SCHEMA_MANIFEST or manifest.get("dataset") != DATASET:
        raise FormatBreadthError("manifest schema/dataset mismatch")
    status = manifest.get("status")
    if status == "unobserved":
        if manifest != _UNOBSERVED_MANIFEST:
            raise FormatBreadthError("unobserved manifest must match the exact first-run stub")
        observed_ready = False
    elif status == "observed":
        observed_ready = True
    else:
        raise FormatBreadthError("manifest status must be exactly unobserved or observed")
    if not write_manifest and not observed_ready:
        raise FormatBreadthError(
            "format-breadth manifest is unobserved; first materialization requires --write-manifest"
        )
    recipe_sha = _canonical_json_sha(recipe)
    if observed_ready and manifest.get("recipe_sha256") != recipe_sha:
        raise FormatBreadthError("manifest recipe_sha256 does not match the current recipe")

    expected_sources = manifest.get("source_sha256") if observed_ready else None
    if expected_sources is not None and not isinstance(expected_sources, dict):
        raise FormatBreadthError("manifest.source_sha256 must be an object")

    enron_source = _source(recipe, "enron")
    govdocs_source = _source(recipe, "govdocs")
    napier_source = _source(recipe, "napier")
    enron_names = ["enron_mail_20150507.tar.gz"]
    govdocs_names = ["000.zip", "001.zip"]
    napier_names = ["ZIP-DEFLATE-tiny.zip", "ZIP-DEFLATE-tiny_zip_hashes.txt"]

    with _cached_source("format-breadth-enron", enron_source, enron_names) as enron_dir, \
            _cached_source("format-breadth-govdocs", govdocs_source, govdocs_names) as govdocs_dir, \
            _cached_source("format-breadth-napier", napier_source, napier_names) as napier_dir:
        enron_sha = _verify_source_hashes(
            enron_source, enron_dir, enron_names,
            observed=(expected_sources or {}).get("enron") if expected_sources else None,
        )
        govdocs_sha = _verify_source_hashes(
            govdocs_source, govdocs_dir, govdocs_names,
            observed=(expected_sources or {}).get("govdocs") if expected_sources else None,
        )
        napier_sha = _verify_source_hashes(
            napier_source, napier_dir, napier_names,
            observed=(expected_sources or {}).get("napier") if expected_sources else None,
        )
        sidecar_expected = _sidecar_archive_sha(
            (napier_dir / napier_names[1]).read_bytes(), archive_filename=napier_names[0]
        )
        if sidecar_expected != napier_sha[napier_names[0]]:
            raise FormatBreadthError("Napier archive SHA-256 does not match its upstream sidecar")

        emails = _select_enron(enron_dir / enron_names[0], seed=seed)
        rtfs = _select_govdocs([govdocs_dir / name for name in govdocs_names])
        zips = _select_napier(napier_dir / napier_names[0])

    files: list[tuple[str, bytes]] = []
    enron_rows = []
    for index, candidate in enumerate(emails, 1):
        output = f"enron-{index:02d}.eml"
        files.append((output, candidate.raw))
        enron_rows.append({"source_member": candidate.member, "output": output,
                           "sender_domain": candidate.sender_domain,
                           "sha256": _sha256_bytes(candidate.raw)})
    govdocs_rows = []
    for index, (archive_name, member, raw) in enumerate(rtfs, 1):
        output = f"govdocs-{index:02d}.rtf"
        files.append((output, raw))
        govdocs_rows.append({"source_archive": archive_name, "source_member": member,
                             "output": output, "sha256": _sha256_bytes(raw)})
    napier_rows = []
    for index, (member, raw, embedded) in enumerate(zips, 1):
        output = f"napier-{index:02d}.zip"
        files.append((output, raw))
        napier_rows.append({"source_member": member, "output": output,
                            "sha256": _sha256_bytes(raw), "embedded_members": embedded})

    actual_manifest = {
        "schema": SCHEMA_MANIFEST,
        "status": "observed",
        "dataset": DATASET,
        "recipe_sha256": recipe_sha,
        "source_sha256": {"enron": enron_sha, "govdocs": govdocs_sha, "napier": napier_sha},
        "napier_sidecar_declared_archive_sha256": sidecar_expected,
        "sources": {
            "enron": {"members": enron_rows},
            "govdocs": {"members": govdocs_rows},
            "napier": {"members": napier_rows},
        },
        "output_counts": {".eml": ENRON_COUNT, ".rtf": GOVDOCS_RTF_COUNT, ".zip": NAPIER_ZIP_COUNT},
    }
    if observed_ready:
        _assert_manifest_matches(actual_manifest, manifest)

    realdocs_hashes, realdocs_digest = _realdocs_hashes(
        realdocs_dir, realdocs_manifest_path
    )
    output_rows = [{"path": path, "sha256": _sha256_bytes(raw)} for path, raw in files]
    overlap = sorted({row["sha256"] for row in output_rows} & realdocs_hashes)
    if overlap:
        raise FormatBreadthError(
            f"format-breadth output overlaps materialized realdocs-v1 by {len(overlap)} SHA-256 value(s)"
        )
    counts: dict[str, int] = {}
    for relative, _raw in files:
        suffix = PurePosixPath(relative).suffix.lower()
        counts[suffix] = counts.get(suffix, 0) + 1
    expected_counts = {".eml": ENRON_COUNT, ".rtf": GOVDOCS_RTF_COUNT, ".zip": NAPIER_ZIP_COUNT}
    if counts != expected_counts or len(files) != sum(expected_counts.values()):
        raise FormatBreadthError(f"output count/extension mismatch: {counts}")

    metadata = {
        "schema": "jseval.raw-corpus-metadata.v1",
        "dataset": DATASET,
        "raw_files": True,
        "corpus_size": len(files),
        "extension_counts": counts,
        "coverage_qualification": _EXPECTED_COVERAGE_QUALIFICATION,
        "recipe_sha256": recipe_sha,
        "manifest_sha256": _canonical_json_sha(actual_manifest),
        "realdocs_v1_overlap": {"sha256_intersection_count": 0,
                                "materialized_file_set_sha256": realdocs_digest},
        "licenses": recipe.get("licenses"),
    }
    dataset_dir.parent.mkdir(parents=True, exist_ok=True)
    _write_dataset(dataset_dir, files, metadata)
    if not observed_ready and write_manifest:
        manifest_path.write_text(
            json.dumps(actual_manifest, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8", newline="\n",
        )
    return metadata


__all__ = ["FormatBreadthError", "materialize_format_breadth"]
