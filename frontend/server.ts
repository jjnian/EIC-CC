import "dotenv/config";
import express from "express";
import { createServer as createViteServer } from "vite";
import path from "path";
import fs from "fs";

function getLLMConfig() {
  try {
    const configPath = path.resolve(process.cwd(), 'llm-config.json');
    if (fs.existsSync(configPath)) {
      const data = fs.readFileSync(configPath, 'utf8');
      return JSON.parse(data);
    }
  } catch (err) {
    console.error("Failed to read llm-config.json", err);
  }
  return {};
}

async function startServer() {
  const app = express();
  const PORT = 3000;

  app.use(express.json());

  app.get("/api/config", (req, res) => {
    const fileConfig = getLLMConfig();
    res.json({
      baseUrl: fileConfig.baseUrl || "https://dashscope.aliyuncs.com/compatible-mode/v1",
      modelName: fileConfig.modelName || "qwen-max"
    });
  });

  app.post("/api/config", (req, res) => {
    try {
      const { baseUrl, modelName } = req.body;
      const configPath = path.resolve(process.cwd(), 'llm-config.json');
      const newConfig = { baseUrl, modelName };
      fs.writeFileSync(configPath, JSON.stringify(newConfig, null, 2), 'utf8');
      res.json({ success: true });
    } catch (err: any) {
      res.status(500).json({ error: err.message });
    }
  });

  // API route for chatting and graph generation
  app.post("/api/chat", async (req, res) => {
    try {
      const { message, history } = req.body;

      const fileConfig = getLLMConfig();
      const baseURL = process.env.LLM_BASE_URL || fileConfig.baseUrl || "https://dashscope.aliyuncs.com/compatible-mode/v1";
      const modelName = process.env.LLM_MODEL_NAME || fileConfig.modelName || "qwen-max";

      const apiKey = process.env.LLM_API_KEY || process.env.GEMINI_API_KEY;

      if (!apiKey) {
        return res.status(400).json({ error: "Missing LLM_API_KEY or GEMINI_API_KEY in environment variables." });
      }

      const schemaString = `
{
  "reply": "A short, helpful assistant reply acknowledging the user's request and explaining the graph updates.",
  "add_nodes": [
    {
      "id": "A unique id for the node, e.g., 'n_123'",
      "label": "The name of the entity, event, or rule",
      "type": "Must be one of: 'entity', 'event', 'rule', 'process', 'data', 'external'",
      "source": "Must be one of: 'derived' (from text) or 'inferred'",
      "props": [
        { "key": "string", "value": "string", "source": "Must be one of: 'derived' or 'inferred'" }
      ]
    }
  ],
  "add_edges": [
    {
      "id": "Unique edge id, e.g., 'e_456'",
      "from": "Source node id",
      "to": "Target node id",
      "label": "Description or verb of the relationship or rule",
      "source": "Must be one of: 'derived' or 'inferred'",
      "rule_driven": true_or_false
    }
  ]
}
      `.trim();

      const systemInstruction = `You are an AI Ontology Developer.
Analyze the user's text to extract real-world relationships, driving events, entities, and rules.
Formulate this as a directed graph.
CRITICAL INSTRUCTION:
1. Explicitly represent rules (type: 'rule') if they drive events.
2. Label ALL properties, nodes, and edges with their 'source' - if it was explicitly mentioned in the user's text, mark it 'derived'. If you imagined it or inferred it with your world knowledge to fill in blanks, mark it 'inferred'.
3. You MUST return ONLY valid JSON strictly matching this schema. NO markdown wrapping, just the raw JSON object.

SCHEMA:
${schemaString}`;

      const prompt = `Here is the user's latest message:\n${message}\n\nPlease generate the corresponding entities and relationships strictly in JSON format matching the given schema.`;

      const fetchRes = await fetch(`${baseURL.replace(/\/$/, '')}/chat/completions`, {
        method: "POST",
        headers: {
          "Authorization": `Bearer ${apiKey}`,
          "Content-Type": "application/json"
        },
        body: JSON.stringify({
          model: modelName,
          messages: [
            { role: "system", content: systemInstruction },
            { role: "user", content: prompt }
          ],
          response_format: { type: "json_object" }
        })
      });

      if (!fetchRes.ok) {
        const errBody = await fetchRes.text();
        throw new Error(`LLM Error: ${fetchRes.status} - ${errBody}`);
      }

      const data = await fetchRes.json();
      let jsonStr = data.choices?.[0]?.message?.content || "{}";

      // Clean up potential markdown formatting just in case
      jsonStr = jsonStr.replace(/^```json/gi, '').replace(/```$/g, '').trim();

      const result = JSON.parse(jsonStr);

      res.json(result);
    } catch (err: any) {
      console.error(err);
      res.status(500).json({ error: err.message });
    }
  });

  // Vite middleware for development
  if (process.env.NODE_ENV !== "production") {
    const vite = await createViteServer({
      server: { middlewareMode: true },
      appType: "spa",
    });
    app.use(vite.middlewares);
  } else {
    const distPath = path.join(process.cwd(), "dist");
    app.use(express.static(distPath));
    app.get("*", (req, res) => {
      res.sendFile(path.join(distPath, "index.html"));
    });
  }

  app.listen(PORT, "0.0.0.0", () => {
    console.log(`Server running on http://localhost:${PORT}`);
  });
}

startServer();
