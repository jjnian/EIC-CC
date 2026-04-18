import json
import re
import os
from pathlib import Path
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
import anthropic

# Load .env if present
env_path = Path(__file__).parent / ".env"
if env_path.exists():
    for line in env_path.read_text().splitlines():
        if "=" in line and not line.startswith("#"):
            k, v = line.split("=", 1)
            os.environ.setdefault(k.strip(), v.strip())

app = FastAPI(title="推演平台 API")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:5173", "http://127.0.0.1:5173"],
    allow_methods=["*"],
    allow_headers=["*"],
)

api_key = os.environ.get("ANTHROPIC_API_KEY")
client = anthropic.Anthropic(api_key=api_key) if api_key else None


class NodeSummary(BaseModel):
    id: str
    label: str
    type: str


class EdgeSummary(BaseModel):
    from_: str
    to: str
    label: str


class ChatRequest(BaseModel):
    message: str
    attachments: list[str] = []
    nodes: list[dict] = []
    edges: list[dict] = []


SYSTEM_PROMPT = """你是推演平台本体图谱助手。用户用自然语言描述实体和关系，你负责理解并输出结构化图谱更新。

节点类型：entity（实体）、process（流程）、event（事件）、data（数据源）、external（外部系统）

返回格式必须是纯JSON（无markdown代码块）：
{
  "reply": "中文回复，说明添加了什么",
  "add_nodes": [{"id":"n_xxxx","label":"名称","type":"entity","x":500,"y":300}],
  "add_edges": [{"id":"e_xxxx","from":"node_id","to":"node_id","label":"关系描述"}]
}

新节点id格式: n_ + 随机4位字母数字，不与已有id重复。
x坐标范围: 100-1300，y坐标范围: 50-650。
如果没有新内容，add_nodes和add_edges为空数组。"""


@app.post("/api/chat")
async def chat(req: ChatRequest):
    nodes_str = json.dumps(req.nodes, ensure_ascii=False)
    edges_str = json.dumps(req.edges, ensure_ascii=False)

    att_note = ""
    if req.attachments:
        att_note = f"\n附件: {', '.join(req.attachments)}（根据文件名推断实体和关系）"

    user_content = f"""当前图谱：
节点({len(req.nodes)}): {nodes_str}
关系({len(req.edges)}): {edges_str}

用户: {req.message or '(无文字)'}{att_note}"""

    if not client:
        raise HTTPException(status_code=503, detail="ANTHROPIC_API_KEY 未配置，请在 backend/.env 中设置")

    message = client.messages.create(
        model="claude-sonnet-4-6",
        max_tokens=1024,
        system=SYSTEM_PROMPT,
        messages=[{"role": "user", "content": user_content}],
    )

    raw = message.content[0].text.strip()

    # Parse JSON, strip markdown fences if present
    raw_clean = re.sub(r"```(?:json)?\n?|```\n?", "", raw).strip()
    try:
        data = json.loads(raw_clean)
    except json.JSONDecodeError:
        m = re.search(r"\{[\s\S]*\}", raw_clean)
        if m:
            data = json.loads(m.group(0))
        else:
            data = {"reply": raw, "add_nodes": [], "add_edges": []}

    return {
        "reply": data.get("reply", "图谱已更新。"),
        "add_nodes": data.get("add_nodes", []),
        "add_edges": data.get("add_edges", []),
    }


@app.get("/api/health")
async def health():
    return {"status": "ok"}
