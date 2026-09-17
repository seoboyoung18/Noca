"""vehicle_names 정규화·해석 계약 테스트."""

from __future__ import annotations

import unittest

from standardization.vehicle_names import (
    normalize_vehicle_name,
    price_tier_of_model,
    resolve_vehicle,
    split_manufacturer,
)


class NormalizeTest(unittest.TestCase):
    def test_연식_괄호를_지운다(self):
        self.assertEqual(normalize_vehicle_name("아반떼AD(16)"), "아반떼")
        self.assertEqual(normalize_vehicle_name("K3(4도어)(16)"), "K3")

    def test_세대코드를_지운다(self):
        self.assertEqual(normalize_vehicle_name("HG그랜져"), "그랜저")
        self.assertEqual(normalize_vehicle_name("LF 쏘나타"), "쏘나타")
        self.assertEqual(normalize_vehicle_name("싼타페DM"), "싼타페")
        self.assertEqual(normalize_vehicle_name("아반떼(20)-CN7"), "아반떼")

    def test_마케팅_수식어를_지운다(self):
        for raw in ("올뉴모닝(17)", "올 뉴 모닝(11)", "올뉴모닝(15)"):
            self.assertEqual(normalize_vehicle_name(raw), "모닝")
        self.assertEqual(normalize_vehicle_name("더넥스트스파크"), "스파크")
        self.assertEqual(normalize_vehicle_name("LF 쏘나타 뉴라이즈"), "쏘나타")

    def test_클래스_시리즈_앞_글자는_모델명이다(self):
        # 'E' 를 세대코드로 보면 '벤츠클래스' 가 되어 마스터와 어긋난다
        self.assertEqual(normalize_vehicle_name("벤츠 E클래스"), "E클래스")
        self.assertEqual(normalize_vehicle_name("벤츠 C클래스"), "C클래스")
        self.assertEqual(normalize_vehicle_name("BMW 5시리즈"), "5시리즈")
        self.assertEqual(normalize_vehicle_name("BMW 3시리즈"), "3시리즈")

    def test_한글만_남겨_이름이_안_되면_영숫자를_남긴다(self):
        self.assertEqual(normalize_vehicle_name("뉴SM3(2012)"), "SM3")

    def test_빈값(self):
        self.assertEqual(normalize_vehicle_name(None), "")
        self.assertEqual(normalize_vehicle_name("   "), "")


class ManufacturerTest(unittest.TestCase):
    def test_제조사와_차종구분을_분리한다(self):
        self.assertEqual(split_manufacturer("현대 / RV"), ("현대", "RV"))
        self.assertEqual(split_manufacturer("기아 / 승용"), ("기아", "승용"))

    def test_표기를_마스터에_맞춘다(self):
        self.assertEqual(split_manufacturer("한국GM")[0], "쉐보레")
        self.assertEqual(split_manufacturer("쌍용 / RV"), ("KG모빌리티", "RV"))
        self.assertEqual(split_manufacturer("르노삼성")[0], "르노코리아")

    def test_차종구분이_없으면_None(self):
        self.assertIsNone(split_manufacturer("현대")[1])
        self.assertEqual(split_manufacturer(None), (None, None))


class ResolveTest(unittest.TestCase):
    def test_마스터에_있는_차종(self):
        ref = resolve_vehicle("아반떼AD(16)")
        self.assertTrue(ref.resolved)
        self.assertEqual(ref.model_name, "아반떼")
        self.assertIsNotNone(ref.price_tier)

    def test_마스터에_없는_차종은_오류가_아니다(self):
        # 코퍼스에만 있는 단종 차종. model_name 은 None 이고 가격대는 남을 수 있다
        ref = resolve_vehicle("에쿠스(09)")
        self.assertFalse(ref.resolved)
        self.assertIsNone(ref.model_name)
        self.assertEqual(ref.normalized, "에쿠스")

    def test_처음_보는_표기도_정규화로_찾는다(self):
        ref = resolve_vehicle("올 뉴  모닝 (99)")
        self.assertEqual(ref.model_name, "모닝")

    def test_빈값은_해석되지_않는다(self):
        ref = resolve_vehicle("")
        self.assertFalse(ref.resolved)
        self.assertEqual(ref.normalized, "")

    def test_가격대는_P1_P4_중_하나다(self):
        for raw in ("아반떼AD(16)", "올뉴모닝(17)", "그랜져IG(17)", "SM6"):
            tier = resolve_vehicle(raw).price_tier
            self.assertIn(tier, {"P1", "P2", "P3", "P4", None})

    def test_모델_대표_가격대(self):
        self.assertEqual(price_tier_of_model("모닝"), "P1")
        self.assertIsNone(price_tier_of_model(None))
        self.assertIsNone(price_tier_of_model("존재하지않는모델"))


if __name__ == "__main__":
    unittest.main()
