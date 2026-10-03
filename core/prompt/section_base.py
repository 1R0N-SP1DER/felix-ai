from abc import ABC, abstractmethod

class PromptSection(ABC):
    def __init__(self, name: str, order: int, stable: bool):
        self.name = name
        self.order = order
        self.stable = stable

    @abstractmethod
    def render(self, context: dict) -> str | None:
        """Render the section. Return None if it has no content."""
        pass
