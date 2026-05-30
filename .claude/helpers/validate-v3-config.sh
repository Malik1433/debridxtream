#!/bin/bash
# V3 Configuration Validation Script

set -e

NODE_BIN="$(command -v node.exe 2>/dev/null || command -v node 2>/dev/null || true)"
if [ -z "$NODE_BIN" ]; then
  echo "Error: Node.js is required for V3 validation"
  exit 1
fi

ERRORS=0
WARNINGS=0

log_error() { echo "ERROR: $1"; ERRORS=$((ERRORS + 1)); }
log_warning() { echo "WARNING: $1"; WARNINGS=$((WARNINGS + 1)); }
log_success() { echo "OK: $1"; }
log_info() { echo "INFO: $1"; }

check_json() {
  local file="$1"
  "$NODE_BIN" - "$file" <<'NODE' >/dev/null 2>&1
const fs = require('fs');
JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
NODE
}

read_json_fields() {
  local file="$1"
  local script="$2"
  "$NODE_BIN" - "$file" <<NODE
const fs = require('fs');
const data = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
$script
NODE
}

echo "Claude Flow V3 Configuration Validation"
echo "======================================="

echo
echo "Checking directory structure..."
SOURCE_ROOT=""
if [ -d "app/src" ]; then
  SOURCE_ROOT="app/src"
elif [ -d "src" ]; then
  SOURCE_ROOT="src"
else
  log_error "Missing required source root: app/src or src"
fi

required_dirs=(".claude" ".claude/helpers" ".claude-flow/metrics" ".claude-flow/security")
if [ -n "$SOURCE_ROOT" ]; then
  required_dirs+=("$SOURCE_ROOT" "$SOURCE_ROOT/main")
fi

for dir in "${required_dirs[@]}"; do
  if [ -d "$dir" ]; then
    log_success "Directory exists: $dir"
  else
    log_error "Missing required directory: $dir"
  fi
done

echo
echo "Checking required files..."
required_files=(
  ".claude/settings.json"
  ".claude/statusline.sh"
  ".claude/helpers/update-v3-progress.sh"
  ".claude-flow/metrics/v3-progress.json"
  ".claude-flow/metrics/performance.json"
  ".claude-flow/security/audit-status.json"
)

for file in "${required_files[@]}"; do
  if [ -f "$file" ]; then
    log_success "File exists: $file"
    case "$file" in
      ".claude/helpers/update-v3-progress.sh")
        if [ -x "$file" ]; then
          log_success "Helper script is executable"
        else
          log_warning "Helper script is not executable: $file"
        fi
        ;;
      ".claude-flow/metrics/v3-progress.json")
        if check_json "$file"; then
          log_success "V3 progress JSON is valid"
          read_json_fields "$file" 'console.log(`Configured for ${data.domains?.total ?? "unknown"} domains, ${data.swarm?.maxAgents ?? "unknown"} agents`);' || true
        else
          log_error "Invalid JSON in v3-progress.json"
        fi
        ;;
    esac
  else
    log_error "Missing required file: $file"
  fi
done

if [ -f "package.json" ]; then
  log_success "File exists: package.json"
else
  log_warning "package.json is not present in this Android repo"
fi

echo
echo "Checking domain structure..."
expected_domains=("task-management" "session-management" "health-monitoring" "lifecycle-management" "event-coordination")
domain_root=""
if [ -n "$SOURCE_ROOT" ] && [ -d "$SOURCE_ROOT/domains" ]; then
  domain_root="$SOURCE_ROOT/domains"
elif [ -d "src/domains" ]; then
  domain_root="src/domains"
fi

for domain in "${expected_domains[@]}"; do
  if [ -n "$domain_root" ] && [ -d "$domain_root/$domain" ]; then
    log_success "Domain directory exists: $domain"
  else
    log_warning "Domain directory missing: $domain (will be created during development)"
  fi
done

echo
echo "Checking git configuration..."
if git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  log_success "Git repository detected"
  current_branch=$(git branch --show-current 2>/dev/null || echo "unknown")
  log_info "Current branch: $current_branch"
  if [ "$current_branch" = "v3" ]; then
    log_success "On V3 development branch"
  else
    log_warning "Not on V3 branch (current: $current_branch)"
  fi
else
  log_error "Not in a Git repository"
fi

echo
echo "Checking Node.js environment..."
if command -v node.exe >/dev/null 2>&1; then
  node_version=$(node.exe --version)
  log_success "Node.js installed: $node_version"
else
  log_error "Node.js not installed"
fi

if command -v npm >/dev/null 2>&1; then
  npm_version=$(npm --version)
  log_success "npm installed: $npm_version"
else
  log_error "npm not installed"
fi

echo
echo "Checking development tools..."
for tool in git; do
  if command -v "$tool" >/dev/null 2>&1; then
    log_success "$tool installed"
  else
    log_error "$tool not installed"
  fi
done

echo
echo "Checking permissions..."
for file in ".claude/statusline.sh" ".claude/helpers/update-v3-progress.sh"; do
  if [ -f "$file" ]; then
    if [ -x "$file" ]; then
      log_success "Executable permissions: $file"
    else
      log_warning "Missing executable permissions: $file"
    fi
  fi
done

echo
echo "Validation Summary"
echo "=================="
if [ "$ERRORS" -eq 0 ] && [ "$WARNINGS" -eq 0 ]; then
  log_success "All checks passed"
  exit 0
elif [ "$ERRORS" -eq 0 ]; then
  log_warning "$WARNINGS warnings found, but no critical errors"
  exit 0
else
  log_error "$ERRORS critical errors found"
  exit 1
fi
