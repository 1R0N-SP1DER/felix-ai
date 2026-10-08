"""
felix-ai Core -- web_search.py
Key-free web search (DuckDuckGo) so search never depends on Gemini's quota. Returns plain results
for the model to read; the model writes the answer.
"""

import html
import re
import urllib.parse

import requests

UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
ENDPOINTS = ("https://html.duckduckgo.com/html/", "https://lite.duckduckgo.com/lite/")


class SearchError(Exception):
    pass


def _text(fragment: str) -> str:
    return html.unescape(re.sub(r"\s+", " ", re.sub(r"<[^>]+>", " ", fragment))).strip()


def _real_url(href: str) -> str:
    href = html.unescape(href)
    if href.startswith("//"):
        href = "https:" + href
    q = urllib.parse.parse_qs(urllib.parse.urlparse(href).query)
    return q["uddg"][0] if "uddg" in q else href


def parse_results(page: str, limit: int = 5) -> list:
    """Results from either DuckDuckGo page layout (html and lite)."""
    links = re.findall(r'<a[^>]+?(?:class=["\'][^"\']*(?:result__a|result-link)[^"\']*["\'])[^>]*?>(.*?)</a>', page, re.DOTALL)
    hrefs = re.findall(r'<a[^>]+?class=["\'][^"\']*(?:result__a|result-link)[^"\']*["\'][^>]*?href=["\']([^"\']+)["\']|'
                       r'<a[^>]+?href=["\']([^"\']+)["\'][^>]*?class=["\'][^"\']*(?:result__a|result-link)[^"\']*["\']', page)
    hrefs = [a or b for a, b in hrefs]
    snippets = re.findall(r'class=["\'][^"\']*(?:result__snippet|result-snippet)[^"\']*["\'][^>]*>(.*?)</(?:a|td|div)>', page, re.DOTALL)
    out = []
    for i, (title, href) in enumerate(zip(links, hrefs)):
        url = _real_url(href)
        if not url.startswith("http") or "duckduckgo.com/y.js" in url:
            continue  # ads
        out.append({"title": _text(title), "url": url, "snippet": _text(snippets[i]) if i < len(snippets) else ""})
        if len(out) >= limit:
            break
    return out


def ddg_search(query: str, limit: int = 5, timeout: float = 10.0, get=None, post=None) -> list:
    query = query.strip()
    if not query:
        raise SearchError("empty query")
    post = post or requests.post
    last = "no results"
    for url in ENDPOINTS:
        try:
            r = post(url, data={"q": query}, headers={"User-Agent": UA}, timeout=timeout)
        except requests.RequestException as e:
            last = f"network error ({type(e).__name__})"
            continue
        if r.status_code != 200:
            last = f"search engine answered {r.status_code}"
            continue
        results = parse_results(r.text, limit)
        if results:
            return results
        last = "no results found"
    raise SearchError(last)


def format_results(query: str, results: list) -> str:
    lines = [f"Web results for '{query}' (raw search results; answer from them and say if they don't settle it):"]
    for i, r in enumerate(results, 1):
        lines.append(f"{i}. {r['title']}" + (f": {r['snippet']}" if r["snippet"] else "") + f"\n   {r['url']}")
    return "\n".join(lines)
