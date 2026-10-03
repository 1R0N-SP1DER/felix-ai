from prompt.section_base import PromptSection
from memory_recall import search_memories

class MemoriesSection(PromptSection):
    def __init__(self):
        super().__init__("memories", 3, False)
    
    def render(self, context: dict) -> str | None:
        user_message = context.get("user_message", "")
        memories = search_memories(user_message)
        if not memories:
            return None
        parts = ["\nBACKGROUND INFO ABOUT FELIX (retrieved for this turn):"]
        for memory in memories:
            parts.append(f"Felix: {memory['fact']}")
        return "\n".join(parts)