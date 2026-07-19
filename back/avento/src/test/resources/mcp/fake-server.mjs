import readline from "node:readline";

const lines = readline.createInterface({ input: process.stdin, crlfDelay: Infinity });

function send(id, result) {
  process.stdout.write(`${JSON.stringify({ jsonrpc: "2.0", id, result })}\n`);
}

lines.on("line", (line) => {
  if (!line.trim()) return;
  const request = JSON.parse(line);
  if (request.id === undefined) return;

  if (request.method === "initialize") {
    send(request.id, {
      protocolVersion: request.params?.protocolVersion ?? "2024-11-05",
      capabilities: { tools: { listChanged: false } },
      serverInfo: { name: "avento-test-mcp", version: "1.0.0" },
    });
    return;
  }

  if (request.method === "tools/list") {
    send(request.id, {
      tools: [
        {
          name: "echo",
          description: "Returns the received text",
          inputSchema: {
            type: "object",
            properties: { text: { type: "string" } },
            required: ["text"],
          },
        },
      ],
    });
    return;
  }

  if (request.method === "tools/call") {
    send(request.id, {
      content: [{ type: "text", text: request.params?.arguments?.text ?? "" }],
      isError: false,
    });
  }
});
