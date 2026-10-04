import json
from prompt.section_base import PromptSection
from memory_store import get_style_profile

class StyleSection(PromptSection):
    def __init__(self):
        super().__init__("style", 4, False)
    
    def render(self, context: dict) -> str | None:
        style = get_style_profile()
        if not style:
            return None
        a = context.get("assistant", "Faze")
        u = context.get("user", "Felix")
        parts = [f"\nHOW {u.upper()} TALKS (mirror the tone, but you are still {a}):"]
        if style.get("tone_notes"):
            parts.append(f"- Tone: {style['tone_notes']}")
        if style.get("formality_level"):
            parts.append(f"- Formality: {style['formality_level']}")
        if style.get("common_phrases"):
            try:
                phrases = json.loads(style["common_phrases"])
                if phrases:
                    parts.append(f"- Phrases {u} often uses: {', '.join(phrases)}")
            except json.JSONDecodeError:
                pass
        return "\n".join(parts)