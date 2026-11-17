// Shared by the eval scripts: an Anthropic client whose key comes from the project's secret file
// (SECRETS_DIR/ANTHROPIC_API_KEY, default ~/.smartfreight/secrets). The key is never printed.
import Anthropic from "@anthropic-ai/sdk";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

export function createAnthropicClient() {
  const dir = process.env.SECRETS_DIR || path.join(os.homedir(), ".smartfreight", "secrets");
  const apiKey = fs.readFileSync(path.join(dir, "ANTHROPIC_API_KEY"), "utf8").replace(/^﻿/, "").trim();
  if (!apiKey) throw new Error(`ANTHROPIC_API_KEY in ${dir} is empty`);
  return new Anthropic({ apiKey });
}
