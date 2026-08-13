const http = require("http");

const server = http.createServer(async (req, res) => {
  if (req.url !== "/v1/responses" || req.method !== "POST") {
    res.writeHead(404);
    return res.end("Not found");
  }

  let body = "";
  req.on("data", c => body += c);

  req.on("end", async () => {
    try {
      const x = JSON.parse(body);

      const allowed = [
        "model","input","instructions","max_output_tokens",
        "reasoning","temperature","top_p","tools","tool_choice",
        "parallel_tool_calls","text"
      ];

      const clean = {};
      for (const k of allowed) {
        if (x[k] !== undefined) clean[k] = x[k];
      }

      const r = await fetch("https://api.groq.com/openai/v1/responses", {
        method: "POST",
        headers: {
          "Authorization": `Bearer ${process.env.GROQ_API_KEY}`,
          "Content-Type": "application/json"
        },
        body: JSON.stringify(clean)
      });

      res.writeHead(r.status, {
        "Content-Type": r.headers.get("content-type") || "application/json"
      });

      res.end(await r.text());
    } catch (e) {
      res.writeHead(500, {"Content-Type":"application/json"});
      res.end(JSON.stringify({error:{message:e.message}}));
    }
  });
});

server.listen(5901, "127.0.0.1", () =>
  console.log("Groq proxy listening on http://127.0.0.1:5901")
);
