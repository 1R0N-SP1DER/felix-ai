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
        parts = ["\nFACTS ABOUT FELIX (the person you are talking to; you are Faze, not Felix):"]
        for memory in memories:
            parts.append(f"- {to_third_person(memory['fact'])}")
        return "\n".join(parts)