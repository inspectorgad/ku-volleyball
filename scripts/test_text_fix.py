import unittest

from text_fix import fix_text, fix_tree


class TextFixTest(unittest.TestCase):
    def test_double_encoded_names_are_repaired(self):
        self.assertEqual(fix_text("InÃ©s Losada"), "Inés Losada")
        self.assertEqual(fix_text("Ã\u0089lodie Lalonde"), "Élodie Lalonde")

    def test_lowercased_double_encoding_is_repaired(self):
        # Seen in NCAA box scores: mangled, then lowercased.
        self.assertEqual(fix_text("Inã©s"), "Inés")
        self.assertEqual(fix_text("Vã®rlan"), "Vîrlan")
        self.assertEqual(fix_text("Kriä\u008dkoviä\u0087"), "Kričković")
        self.assertEqual(fix_text("Spå\u0082awska"), "Spławska")

    def test_correct_spellings_are_left_alone(self):
        for name in ("Inés Losada", "Désirée", "Una Vajagić", "Zoë", "Kelnárová", "Tulsa"):
            self.assertEqual(fix_text(name), name)

    def test_whole_documents(self):
        doc = {"players": [{"name": "Buriloviä\u0087", "k": 3}], "team": "Utah"}
        self.assertEqual(fix_tree(doc), {"players": [{"name": "Burilović", "k": 3}], "team": "Utah"})


if __name__ == "__main__":
    unittest.main()
