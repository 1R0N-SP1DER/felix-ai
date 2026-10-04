"""
felix-ai Core — prompt_assembler.py
Builds the system message: identity framing, then Felix's stored
rules/memories/style, explicitly reframed as third-person background
info ABOUT Felix.
"""

import importlib.util
import os
import glob
from prompt.section_base import PromptSection

# Registry to hold discovered sections
_SECTIONS = []

def _discover_sections():
    if _SECTIONS:
        return
    
    sections_dir = os.path.join(os.path.dirname(__file__), "prompt", "sections")
    section_files = glob.glob(os.path.join(sections_dir, "*.py"))
    
    for file_path in section_files:
        if "__init__.py" in file_path:
            continue
        
        module_name = os.path.basename(file_path)[:-3]
        spec = importlib.util.spec_from_file_location(module_name, file_path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        
        for name, obj in vars(module).items():
            if isinstance(obj, type) and issubclass(obj, PromptSection) and obj is not PromptSection:
                _SECTIONS.append(obj())
    
    _SECTIONS.sort(key=lambda s: s.order)

_discover_sections()

# Cache for stable sections
_STABLE_CACHE = None
_NAMES = {"assistant": "Faze", "user": "Felix"}


def set_names(assistant: str, user: str) -> None:
    """Change who 'you' and 'I' are in every prompt section. Clears the
    stable-prefix cache, so the next prompt is rebuilt with the new names."""
    global _STABLE_CACHE
    _NAMES["assistant"], _NAMES["user"] = assistant, user
    _STABLE_CACHE = None


def _context(user_message: str = "") -> dict:
    return {"user_message": user_message, "assistant": _NAMES["assistant"], "user": _NAMES["user"]}

def build_system_message(user_message: str, include_memories: bool = True) -> str:
    global _STABLE_CACHE
    
    if _STABLE_CACHE is None:
        _STABLE_CACHE = []
        for section in _SECTIONS:
            if section.stable:
                content = section.render(_context())
                if content:
                    _STABLE_CACHE.append(content)
        _STABLE_CACHE = "\n".join(_STABLE_CACHE)
    
    parts = [_STABLE_CACHE]
    
    context = _context(user_message)
    for section in _SECTIONS:
        if not section.stable:
            if section.name == "memories" and not include_memories:
                continue
            content = section.render(context)
            if content:
                parts.append(content)
                
    return "\n".join(parts)


def build_static_system_message() -> str:
    """Persona, rules and style with NO per-turn memories, so the
    prompt prefix is identical every turn and llama-server's KV cache
    stays warm."""
    return build_system_message("", include_memories=False)


def build_turn_context(user_message: str) -> str:
    """The retrieved-memories block for this one message, or ''. It is
    attached to the user turn, never the system prompt."""
    for section in _SECTIONS:
        if section.name == "memories":
            return section.render(_context(user_message)) or ""
    return ""


if __name__ == "__main__":
    msg = build_system_message("Faze")
    print(msg)
    print(f"\n--- ({len(msg)} characters) ---")