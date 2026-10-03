from prompt.section_base import PromptSection
from memory_store import get_active_rules

class RulesSection(PromptSection):
    def __init__(self):
        super().__init__("rules", 2, False)
    
    def render(self, context: dict) -> str | None:
        rules = get_active_rules()
        if not rules:
            return None
        parts = []
        for rule in rules:
            parts.append(f"- {rule['rule_text']}")
        return "\n".join(parts)