import re

def match_intent(user_message):
    ask_gemini = re.search(r"^ask gemini[,:]?\s+(.+)", user_message, re.IGNORECASE)
    if ask_gemini:
        return {"tool": "delegate_to_gemini", "parameters": {"request": ask_gemini.group(1)}}
    
    search_web = re.search(r"^search( the web)?( for)?[,:]?\s+(.+)", user_message, re.IGNORECASE)
    if search_web:
        return {"tool": "search_web", "parameters": {"query": search_web.group(3)}}
        
    return None
