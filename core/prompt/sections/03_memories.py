from prompt.section_base import PromptSection
from memory_recall import search_memories
from pronouns import to_third_person

class MemoriesSection(PromptSection):
    def __init__(self):
        super().__init__("memories", 3, False)
    
    def render(self, context: dict) -> str | None:
        user_message = context.get("user_message", "")
        memories = search_memories(user_message)
        if not memories:
            return None
        a = context.get("assistant", "Faze")
        u = context.get("user", "Felix")
        parts = [f"\nFACTS ABOUT {u.upper()} (the person you are talking to; you are {a}, not {u}):"]
        for memory in memories:
            parts.append(f"- {to_third_person(memory['fact'], u)}")
        return "\n".join(parts)