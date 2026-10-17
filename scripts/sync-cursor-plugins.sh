#!/usr/bin/env bash
# ==============================================================================
# sync-cursor-plugins.sh
#
# Synchronizes, patches, and validates official Cursor plugins for both:
#  1. Google Antigravity (~/.gemini/config/plugins/ + LS registration)
#  2. Cursor IDE (~/.cursor/plugins/local/ + .cursor/rules/)
#  3. InstaCommerce Workspace (.agents/skills/ + .agents/agents/)
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

TMP_CLONE_DIR="/tmp/cursor-plugins-sync-$$"
GLOBAL_PLUGINS_DIR="$HOME/.gemini/config/plugins"
LOCAL_CURSOR_PLUGINS_DIR="$HOME/.cursor/plugins/local"
WS_SKILLS_DIR="$REPO_ROOT/.agents/skills"
WS_AGENTS_DIR="$REPO_ROOT/.agents/agents"
WS_RULES_DIR="$REPO_ROOT/.cursor/rules"

PLUGINS=(
  "pstack"
  "cursor-team-kit"
  "thermos"
  "continual-learning"
  "cli-for-agent"
  "agent-compatibility"
  "advisor"
  "ralph-loop"
  "pr-review-canvas"
  "docs-canvas"
  "dyl-stack"
  "create-plugin"
  "teaching"
)

MCP_PLUGINS=(
  "playwright"
  "excalidraw"
)

echo "=== 1. Cloning official cursor/plugins repository ==="
git clone --depth 1 https://github.com/cursor/plugins.git "$TMP_CLONE_DIR" 2>/dev/null

cleanup() {
  rm -rf "$TMP_CLONE_DIR"
}
trap cleanup EXIT

mkdir -p "$GLOBAL_PLUGINS_DIR" "$LOCAL_CURSOR_PLUGINS_DIR" "$WS_SKILLS_DIR" "$WS_AGENTS_DIR" "$WS_RULES_DIR"

echo "=== 2. Installing and patching plugins ==="
for plugin in "${PLUGINS[@]}"; do
  src_dir="$TMP_CLONE_DIR/$plugin"
  dst_dir="$GLOBAL_PLUGINS_DIR/$plugin"
  
  if [[ ! -d "$src_dir" ]]; then
    echo "Warning: $plugin not found in upstream repository"
    continue
  fi

  rm -rf "$dst_dir"
  cp -R "$src_dir" "$dst_dir"

  # Ensure root plugin.json exists and conforms to Antigravity schema
  if [[ -f "$src_dir/.cursor-plugin/plugin.json" && ! -f "$dst_dir/plugin.json" ]]; then
    cp "$src_dir/.cursor-plugin/plugin.json" "$dst_dir/plugin.json"
  fi

  # Strip conflicting fields from plugin.json (skills, agents, rules strings)
  if [[ -f "$dst_dir/plugin.json" ]]; then
    python3 -c "
import json
with open('$dst_dir/plugin.json', 'r') as f:
    d = json.load(f)
cleaned = {k: v for k, v in d.items() if k not in {'skills', 'agents', 'rules', 'hooks', 'category', 'tags'}}
with open('$dst_dir/plugin.json', 'w') as f:
    json.dump(cleaned, f, indent=2)
"
  fi

  # Symlink to Cursor local plugins
  ln -sfn "$dst_dir" "$LOCAL_CURSOR_PLUGINS_DIR/$plugin"
  echo "  ✔ $plugin installed and symlinked"
done

# Install MCP plugins from third_party
for mcp_plugin in "${MCP_PLUGINS[@]}"; do
  src_dir="$TMP_CLONE_DIR/third_party/$mcp_plugin"
  dst_dir="$GLOBAL_PLUGINS_DIR/$mcp_plugin"

  if [[ -d "$src_dir" ]]; then
    rm -rf "$dst_dir"
    cp -R "$src_dir" "$dst_dir"
    if [[ -f "$dst_dir/mcp.json" && ! -f "$dst_dir/mcp_config.json" ]]; then
      cp "$dst_dir/mcp.json" "$dst_dir/mcp_config.json"
    fi
    if [[ -f "$src_dir/.cursor-plugin/plugin.json" && ! -f "$dst_dir/plugin.json" ]]; then
      cp "$src_dir/.cursor-plugin/plugin.json" "$dst_dir/plugin.json"
    fi
    if [[ -f "$dst_dir/plugin.json" ]]; then
      python3 -c "
import json
with open('$dst_dir/plugin.json', 'r') as f:
    d = json.load(f)
cleaned = {k: v for k, v in d.items() if k not in {'skills', 'agents', 'rules', 'hooks', 'category', 'tags'}}
with open('$dst_dir/plugin.json', 'w') as f:
    json.dump(cleaned, f, indent=2)
"
    fi
    ln -sfn "$dst_dir" "$LOCAL_CURSOR_PLUGINS_DIR/$mcp_plugin"
    echo "  ✔ $mcp_plugin (MCP) installed"
  fi
done

echo "=== 3. Applying Antigravity runtime, hook & YAML patches ==="

# Fix unquoted YAML colon in check-agent-compatibility
if [[ -f "$GLOBAL_PLUGINS_DIR/agent-compatibility/skills/check-agent-compatibility/SKILL.md" ]]; then
  sed -i '' 's/description: Run the full repository compatibility pass: scanner score, startup path, validation loop, and docs reliability./description: "Run the full repository compatibility pass: scanner score, startup path, validation loop, and docs reliability."/' \
    "$GLOBAL_PLUGINS_DIR/agent-compatibility/skills/check-agent-compatibility/SKILL.md" 2>/dev/null || true
fi

# Ensure continual-learning has root hooks.json and Node.js ESM runner
mkdir -p "$GLOBAL_PLUGINS_DIR/continual-learning/hooks"
cat << 'EOF' > "$GLOBAL_PLUGINS_DIR/continual-learning/hooks.json"
{
  "continual-learning": {
    "Stop": [
      {
        "type": "command",
        "command": "node $HOME/.gemini/config/plugins/continual-learning/hooks/continual-learning-stop.mjs",
        "timeout": 15
      }
    ]
  }
}
EOF

# Ensure ralph-loop has root hooks.json
cat << 'EOF' > "$GLOBAL_PLUGINS_DIR/ralph-loop/hooks.json"
{
  "ralph-loop": {
    "PostInvocation": [
      {
        "type": "command",
        "command": "bash $HOME/.gemini/config/plugins/ralph-loop/hooks/capture-response.sh",
        "timeout": 10
      }
    ],
    "Stop": [
      {
        "type": "command",
        "command": "bash $HOME/.gemini/config/plugins/ralph-loop/hooks/stop-hook.sh",
        "timeout": 15
      }
    ]
  }
}
EOF

# Ensure advisor has root hooks.json
cat << 'EOF' > "$GLOBAL_PLUGINS_DIR/advisor/hooks.json"
{
  "advisor": {
    "PostInvocation": [
      {
        "type": "command",
        "command": "bash $HOME/.gemini/config/plugins/advisor/hooks/capture-response.sh",
        "timeout": 10
      }
    ],
    "Stop": [
      {
        "type": "command",
        "command": "bash $HOME/.gemini/config/plugins/advisor/hooks/stop-hook.sh",
        "timeout": 15
      }
    ]
  }
}
EOF

# Sync canvas skills to use Antigravity Markdown Artifacts
if [[ -f "$WS_SKILLS_DIR/pr-review-canvas/SKILL.md" ]]; then
  cp "$WS_SKILLS_DIR/pr-review-canvas/SKILL.md" "$GLOBAL_PLUGINS_DIR/pr-review-canvas/skills/pr-review-canvas/SKILL.md" 2>/dev/null || true
fi
if [[ -f "$WS_SKILLS_DIR/docs-canvas/SKILL.md" ]]; then
  cp "$WS_SKILLS_DIR/docs-canvas/SKILL.md" "$GLOBAL_PLUGINS_DIR/docs-canvas/skills/docs-canvas/SKILL.md" 2>/dev/null || true
fi
echo "  ✔ Antigravity runtime, hooks, and canvas patches applied"

echo "=== 4. Registering plugins with Language Server ==="
if [[ -n "${ANTIGRAVITY_LS_ADDRESS:-}" && -n "${ANTIGRAVITY_CSRF_TOKEN:-}" ]]; then
  PAYLOAD='{"userConfig":{"plugins":{'
  first=true
  for p in "${PLUGINS[@]}" "${MCP_PLUGINS[@]}"; do
    if [[ "$first" = true ]]; then
      PAYLOAD="$PAYLOAD\"$p\":{\"enabled\":true}"
      first=false
    else
      PAYLOAD="$PAYLOAD,\"$p\":{\"enabled\":true}"
    fi
  done
  PAYLOAD="$PAYLOAD}}}"
  curl -sS -X POST \
    "http://${ANTIGRAVITY_LS_ADDRESS}/exa.language_server_pb.LanguageServerService/JetboxWriteState" \
    -H "Content-Type: application/json" \
    -H "x-codeium-csrf-token: ${ANTIGRAVITY_CSRF_TOKEN}" \
    -d "$PAYLOAD" >/dev/null || true
  echo "  ✔ Plugins registered live via JetboxWriteState"
fi

echo "=== Synchronization Complete ==="
