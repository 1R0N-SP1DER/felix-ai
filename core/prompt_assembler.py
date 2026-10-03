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

def build_system_message(user_message: str) -> str:
    global _STABLE_CACHE
    
    if _STABLE_CACHE is None:
        _STABLE_CACHE = []
        for section in _SECTIONS:
            if section.stable:
                content = section.render({})
                if content:
                    _STABLE_CACHE.append(content)
        _STABLE_CACHE = "\n".join(_STABLE_CACHE)
    
    parts = [_STABLE_CACHE]
    
    context = {"user_message": user_message}
    for section in _SECTIONS:
        if not section.stable:
            content = section.render(context)
            if content:
                parts.append(content)
                
    return "\n".join(parts)


if __name__ == "__main__":
    msg = build_system_message("Faze")
    print(msg)
    print(f"\n--- ({len(msg)} characters) ---")