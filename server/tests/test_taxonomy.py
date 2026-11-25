import json
import tempfile
import unittest
from pathlib import Path

from server.catalogue.taxonomy import DEFAULT_TAXONOMY, Taxonomy, classify


class TaxonomyTest(unittest.TestCase):
    def test_simplified_traditional_and_modified_labels_share_categories(self):
        examples = {
            "fantasy": ["玄幻", "奇幻", "玄幻奇幻点击榜", "異界熱門榜"],
            "martial": ["武侠", "武俠", "仙侠修真", "修仙完本榜"],
            "urban": ["都市生活", "都市现实", "現實職場", "鄉村新書"],
            "romance": ["现代言情", "現代言情", "古代言情", "现言", "古言", "都市言情", "玄幻言情", "女頻甜寵榜"],
            "history": ["历史军事", "歷史軍事", "架空历史", "三國人氣榜"],
            "scifi": ["科幻", "科幻未来", "星際末世", "无限流", "無限流"],
            "mystery": ["悬疑灵异", "懸疑靈異", "恐怖推理", "偵探新作"],
            "gaming": ["游戏竞技", "遊戲競技", "网游", "電競體育", "籃球競技"],
            "fanfic": ["同人", "二次元", "动漫同人", "輕小說", "科幻同人"],
            "classics": ["出版", "四大名著", "經典文學", "诗词散文", "國學古籍"],
        }
        for category_id, labels in examples.items():
            for label in labels:
                with self.subTest(label=label):
                    self.assertEqual(category_id, classify(label).category_id)

    def test_filters_do_not_become_categories(self):
        result = classify("女生古言完结人气排行榜")
        self.assertEqual("romance", result.category_id)
        self.assertEqual(("female", "completed", "ranking", "popular"), result.filter_ids)
        self.assertEqual("other", classify("男频完结排行榜").category_id)
        self.assertEqual(("male", "completed", "ranking"), classify("男频完结排行榜").filter_ids)

    def test_label_genre_before_group_and_generic_label_uses_group(self):
        self.assertEqual("romance", classify("古言", group="玄幻奇幻").category_id)
        result = classify("完本排行榜", group="男頻都市生活")
        self.assertEqual("urban", result.category_id)
        self.assertEqual(("male", "completed", "ranking"), result.filter_ids)

    def test_english_alias_boundaries_and_case_width(self):
        self.assertEqual("scifi", classify("TOP 10 SCI-FI").category_id)
        self.assertEqual("scifi", classify("science_fiction new releases").category_id)
        self.assertEqual("fantasy", classify("ＦＡＮＴＡＳＹ").category_id)
        self.assertEqual(("female",), classify("female fantasy").filter_ids)
        self.assertEqual(("male",), classify("male fantasy").filter_ids)
        self.assertEqual((), classify("topology malefactor femalevolent").filter_ids)
        self.assertEqual("other", classify("fantasyland scifiles romanceable").category_id)

    def test_unknown_and_empty_labels_cannot_expand_catalogue(self):
        self.assertEqual(11, len(DEFAULT_TAXONOMY.categories))
        for label in (None, "", "   ", "未知分类", "全部", "123"):
            self.assertEqual("other", classify(label).category_id)
        outputs = {classify(f"站點獨有分類{index}").category_id for index in range(5000)}
        self.assertEqual({"other"}, outputs)
        self.assertEqual(11, len(DEFAULT_TAXONOMY.categories))

    def test_explicit_override_is_bounded_and_retains_filters(self):
        result = classify("女频玄幻完结", category_override="romance")
        self.assertEqual("romance", result.category_id)
        self.assertEqual(("female", "completed"), result.filter_ids)
        with self.assertRaises(ValueError):
            classify("玄幻", category_override="new dynamic category")

    def test_source_override_respects_complete_opaque_source_id(self):
        config = json.loads(DEFAULT_TAXONOMY.path.read_text(encoding="utf-8"))
        source = "https://fixture.invalid/?edition=1#source-a"
        config["source_overrides"] = {source: {"奇幻": "romance", "TＯＰ": "history"}}
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "taxonomy.json"
            path.write_text(json.dumps(config), encoding="utf-8")
            taxonomy = Taxonomy(path)
            self.assertEqual("romance", taxonomy.classify("奇幻", source_id=source).category_id)
            self.assertEqual("history", taxonomy.classify("top", source_id=source).category_id)
            self.assertEqual("fantasy", taxonomy.classify("奇幻", source_id="https://fixture.invalid/").category_id)
            self.assertEqual("mystery", taxonomy.classify("奇幻", source_id=source, category_override="mystery").category_id)

    def test_invalid_override_configuration_fails_before_serving(self):
        config = json.loads(DEFAULT_TAXONOMY.path.read_text(encoding="utf-8"))
        config["source_overrides"] = {"fixture": {"unknown": "unbounded category"}}
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "taxonomy.json"
            path.write_text(json.dumps(config), encoding="utf-8")
            with self.assertRaises(ValueError):
                Taxonomy(path)

    def test_transport_contract_uses_ids_and_separate_filter_array(self):
        self.assertEqual({"categoryId": "romance", "filterIds": ["female", "completed"]},
                         classify("女頻古言完結").as_dict())
        self.assertEqual(1, DEFAULT_TAXONOMY.version)
        self.assertEqual("other", DEFAULT_TAXONOMY.categories[-1]["id"])


if __name__ == "__main__":
    unittest.main()
