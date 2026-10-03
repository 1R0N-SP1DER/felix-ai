"""
felix-ai Core -- pronouns.py
Memory facts are stored in Felix's own voice ("My phone is a Redmi
10C", "I like anime"). Shown to a small model as-is, "I" and "my"
read as the model's own, which is how it ends up claiming to be
Felix. This rewrites a fact into third person ("Felix's phone is a
Redmi 10C", "Felix likes anime") so there is no first person left to
confuse.
"""

import re

_ADVERBS = (
    "often|always|usually|really|also|just|sometimes|never|still|strongly|probably|"
    "mostly|mainly|definitely|actually|currently|only|rarely|generally|especially|"
    "personally|genuinely|totally|honestly"
)

# Verbs whose "third person" is not "add s" or that must stay as written.
_IRREGULAR = {
    "am": "is", "have": "has", "do": "does", "go": "goes", "don't": "doesn't",
    "can": "can", "can't": "can't", "cannot": "cannot", "will": "will", "won't": "won't",
    "would": "would", "should": "should", "could": "could", "must": "must",
    "may": "may", "might": "might", "was": "was", "were": "was", "had": "had",
    "did": "did", "didn't": "didn't", "need": "needs", "feed": "feeds",
}
_PAST = set(
    "went saw made bought got took gave found knew thought said told felt ran ate drank "
    "wrote left built spent lost met won began came became kept held heard sat stood "
    "understood paid sold brought caught taught chose drove flew grew wore broke spoke "
    "forgot".split()
)

_CONTRACTIONS = [
    (r"\bI(?:'m|’m)\b", "is"), (r"\bI am\b", "is"),
    (r"\bI(?:'ve|’ve)\b", "has"), (r"\bI have\b", "has"),
    (r"\bI(?:'d|’d)\b", "would"), (r"\bI(?:'ll|’ll)\b", "will"),
]


def _third(verb: str) -> str:
    low = verb.lower()
    if low in _IRREGULAR:
        return _IRREGULAR[low]
    if low in _PAST or low.endswith("ed") or low.endswith("ing"):
        return verb
    if re.search(r"(s|sh|ch|x|z|o)$", low):
        return verb + "es"
    if re.search(r"[^aeiou]y$", low):
        return verb[:-1] + "ies"
    return verb + "s"


def _at_sentence_start(s: str, pos: int) -> bool:
    before = s[:pos].rstrip()
    return not before or before[-1] in ".!?\n"


def to_third_person(fact: str) -> str:
    s = fact

    for pattern, verb in _CONTRACTIONS:
        s = re.sub(pattern,
                   lambda m, v=verb: ("Felix " if _at_sentence_start(m.string, m.start()) else "he ") + v,
                   s)

    def subject_verb(m):
        subj = "Felix" if _at_sentence_start(m.string, m.start()) else "he"
        adverbs, verb = m.group(1) or "", m.group(2)
        return f"{subj}{adverbs} {_third(verb)}"

    s = re.sub(rf"\bI((?:\s+(?:{_ADVERBS}))*)\s+([A-Za-z']+)", subject_verb, s)
    s = re.sub(r"\bI\b",
               lambda m: "Felix" if _at_sentence_start(m.string, m.start()) else "he", s)

    s = re.sub(r"\bmyself\b", "himself", s, flags=re.IGNORECASE)
    s = re.sub(r"\bmine\b", "his", s, flags=re.IGNORECASE)
    s = re.sub(r"\bme\b", "him", s, flags=re.IGNORECASE)
    s = re.sub(r"\b[Mm]y\b",
               lambda m: "Felix's" if _at_sentence_start(m.string, m.start()) else "his", s)
    return s
