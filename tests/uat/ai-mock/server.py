from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import re


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/health":
            self.respond(200, {"status": "UP"})
            return
        if self.path == "/debug/source-count":
            self.respond(200, {
                "sourceKeyCount": getattr(self.server, "last_source_key_count", 0),
                "profileTokenCount": getattr(self.server, "last_profile_token_count", 0),
                "interactionTokenCount": getattr(self.server, "last_interaction_token_count", 0),
                "messageCount": getattr(self.server, "last_message_count", 0),
                "promptLength": getattr(self.server, "last_prompt_length", 0),
                "postCount": getattr(self.server, "post_count", 0),
                "lastPath": getattr(self.server, "last_path", ""),
            })
            return
        self.respond(404, {"error": "not_found"})

    def do_POST(self):
        self.server.post_count = getattr(self.server, "post_count", 0) + 1
        self.server.last_path = self.path
        if self.path != "/v1/chat/completions":
            self.respond(404, {"error": "not_found"})
            return
        try:
            request = json.loads(self.read_body() or b"{}")
            messages = request.get("messages", [])
            prompt = "\n".join(str(item.get("content", "")) for item in messages)
            # The OpenAI-compatible client may serialize prompt formatting differently;
            # source keys themselves are stable and contain only a type plus UUID.
            keys = list(dict.fromkeys(re.findall(
                r"\b(?:profile|interaction):[0-9a-fA-F-]{8,}\b", prompt
            )))
            self.server.last_source_key_count = len(keys)
            self.server.last_profile_token_count = prompt.count("profile:")
            self.server.last_interaction_token_count = prompt.count("interaction:")
            self.server.last_message_count = len(messages)
            self.server.last_prompt_length = len(prompt)
            result = {
                "executiveSummary": "UAT 契约模型：已基于现有客户画像和时间线生成只读分析。",
                "goals": ["以已记录信息为准，未出现的信息保持未确认"],
                "motivations": ["未确认"],
                "progress": ["已完成现有证据整理"],
                "concerns": ["需要销售继续核实关键需求"],
                "decisionParticipants": ["未确认"],
                "productFit": "需要结合下一次有效沟通继续判断",
                "risks": ["信息完整度不足"],
                "recommendedActions": ["在下次沟通中确认目标、时间和主要顾虑"],
                "nextFollowUpSuggestion": "根据当前提醒时间跟进，并补充可验证结果",
                "unresolvedQuestions": ["客户最终目标和决策条件是什么"],
                "citations": [{"sourceKey": key} for key in keys[:3]],
            }
            body = {
                "choices": [{"message": {"content": json.dumps(result, ensure_ascii=False)}}],
                "usage": {"prompt_tokens": 32, "completion_tokens": 48},
            }
            self.respond(200, body)
        except (ValueError, json.JSONDecodeError):
            self.respond(400, {"error": "invalid_json"})

    def read_body(self):
        if self.headers.get("Transfer-Encoding", "").lower() != "chunked":
            length = int(self.headers.get("Content-Length", "0"))
            return self.rfile.read(length)
        chunks = []
        while True:
            size_line = self.rfile.readline().strip().split(b";", 1)[0]
            size = int(size_line, 16)
            if size == 0:
                while self.rfile.readline().strip():
                    pass
                break
            chunks.append(self.rfile.read(size))
            self.rfile.read(2)
        return b"".join(chunks)

    def respond(self, status, body):
        encoded = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def log_message(self, _format, *_args):
        return


ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
