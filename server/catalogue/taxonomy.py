"""Bounded server catalogue labels, driven only by taxonomy.json.

Raw routes stay intact; this module only assigns stable presentation IDs.
"""

from __future__ import annotations

import json
import re
import unicodedata
from dataclasses import dataclass
from pathlib import Path
from typing import Any


def _normalize(value: str) -> str:
    return unicodedata.normalize("NFKC", value).casefold().strip()


def _pattern(term: str) -> re.Pattern[str]:
    normalized = _normalize(term)
    # ASCII aliases require whole words: "female" must never imply "male",
    # and "topology" must never imply the ranking alias "top".
    if normalized.isascii():
        words = re.findall(r"[a-z0-9]+", normalized)
        return re.compile(r"(?<![a-z0-9])" + r"[\s\W_]*".join(map(re.escape, words)) + r"(?![a-z0-9])")
    return re.compile(re.escape(normalized))


@dataclass(frozen=True)
class Classification:
    category_id: str
    filter_ids: tuple[str, ...] = ()

    def as_dict(self) -> dict[str, Any]:
        return {"categoryId": self.category_id, "filterIds": list(self.filter_ids)}


class Taxonomy:
    def __init__(self, path: str | Path | None = None):
        self.path = Path(path) if path is not None else Path(__file__).with_name("taxonomy.json")
        self.config = json.loads(self.path.read_text(encoding="utf-8"))
        self.version: int = self.config["version"]
        self.categories = tuple({"id": item["id"], "label": item["label"]}
                                for item in self.config["categories"])
        self.filters = tuple({"id": item["id"], "label": item["label"]}
                             for item in self.config["filters"])
        self._ids = {item["id"] for item in self.categories}
        self.fallback: str = self.config["fallback"]
        if len(self._ids) != len(self.categories) or self.fallback not in self._ids:
            raise ValueError("Taxonomy category IDs must be unique and contain the fallback")
        priority = self.config["match_priority"]
        if len(priority) != len(set(priority)) or set(priority) != self._ids - {self.fallback}:
            raise ValueError("Match priority must list each non-fallback category once")
        if len({item["id"] for item in self.filters}) != len(self.filters):
            raise ValueError("Taxonomy filter IDs must be unique")
        category_terms = {item["id"]: tuple(_pattern(term) for term in item["terms"])
                          for item in self.config["categories"]}
        self._category_rules = tuple((category_id, category_terms[category_id]) for category_id in priority)
        self._filter_rules = tuple((item["id"], tuple(_pattern(term) for term in item["terms"]))
                                   for item in self.config["filters"])
        self._overrides = {
            source_id: {_normalize(label): category_id for label, category_id in labels.items()}
            for source_id, labels in self.config.get("source_overrides", {}).items()
        }
        if any(category_id not in self._ids for labels in self._overrides.values()
               for category_id in labels.values()):
            raise ValueError("Source overrides must reference existing taxonomy category IDs")

    def classify(self, label: str | None, *, group: str | None = None,
                 source_id: str | None = None, category_override: str | None = None) -> Classification:
        """Label genre wins over group context; filters remain separate and may combine.

        An explicit category override or a configured source/label override wins.
        Overrides may only choose an existing ID, so labels never expand the taxonomy.
        Source IDs are opaque and are never normalized or reduced to hostnames.
        """
        label_text = _normalize(label or "")
        group_text = _normalize(group or "")
        filters = tuple(filter_id for filter_id, patterns in self._filter_rules
                        if any(pattern.search(text) for pattern in patterns
                               for text in (label_text, group_text)))
        category_id = category_override
        if category_id is None and source_id is not None:
            category_id = self._overrides.get(source_id, {}).get(label_text)
        if category_id is not None:
            if category_id not in self._ids:
                raise ValueError(f"Unknown category override: {category_id}")
            return Classification(category_id, filters)
        for text in (label_text, group_text):
            for candidate, patterns in self._category_rules:
                if any(pattern.search(text) for pattern in patterns):
                    return Classification(candidate, filters)
        return Classification(self.fallback, filters)


DEFAULT_TAXONOMY = Taxonomy()


def classify(label: str | None, *, group: str | None = None,
             source_id: str | None = None, category_override: str | None = None) -> Classification:
    return DEFAULT_TAXONOMY.classify(label, group=group, source_id=source_id,
                                     category_override=category_override)
