#!/usr/bin/env python3
"""
Find and search through Claude Code conversations.

Usage:
    ./find_conversations.py list [--project PATH]           # List all conversations
    ./find_conversations.py recent [N] [--project PATH]     # List N most recent (default 10)
    ./find_conversations.py search "scroll" [--project PATH]  # Search for keyword
    ./find_conversations.py export SESSION_ID [OUTPUT_PATH] # Export specific conversation
    ./find_conversations.py copy-last [--project PATH]      # Copy last assistant message to clipboard

Options:
    --project PATH    Filter by project path (e.g., --project ~/myproject or --project .)
"""

import json
import sys
import os
import re
import subprocess
import platform
from pathlib import Path
from datetime import datetime
from collections import defaultdict
from urllib.parse import urlparse

CLAUDE_DIR = Path.home() / ".claude"
PROJECTS_DIR = CLAUDE_DIR / "projects"

def normalize_path(path_str):
    """Normalize a path string to absolute path."""
    path = Path(path_str).expanduser().resolve()
    return str(path)

def is_meaningful(text):
    """Filter out system/meta messages."""
    text = text.strip()
    if not text:
        return False
    if text.startswith('<command-name>'):
        return False
    if text.startswith('<local-command-stdout>'):
        return False
    if text.startswith('[Request interrupted'):
        return False
    return True

def shorten_path(file_path):
    """Shorten a file path for display."""
    if not file_path:
        return ''
    home = str(Path.home())
    if file_path.startswith(home):
        return '~' + file_path[len(home):]
    return file_path

def generate_tool_annotation(tool_use):
    """Generate a one-line annotation string for a tool_use block."""
    name = tool_use.get('name', '')
    inp = tool_use.get('input', {})

    if name == 'Read':
        return f"Read: {shorten_path(inp.get('file_path', ''))}"
    elif name == 'Write':
        return f"Wrote: {shorten_path(inp.get('file_path', ''))}"
    elif name == 'Edit':
        return f"Edited: {shorten_path(inp.get('file_path', ''))}"
    elif name == 'WebSearch':
        return f"Searched web: \"{inp.get('query', '')}\""
    elif name == 'WebFetch':
        url = inp.get('url', '')
        parsed = urlparse(url)
        short_url = parsed.netloc + parsed.path
        return f"Fetched: {short_url}"
    elif name == 'Task':
        return f"Launched agent: {inp.get('description', '')}"
    elif name == 'Bash':
        desc = inp.get('description', '')
        cmd = inp.get('command', '')
        label = desc if desc else (cmd[:60] + ('...' if len(cmd) > 60 else ''))
        return f"Ran: `{label}`"
    elif name == 'Glob':
        return f"Found files: {inp.get('pattern', '')}"
    elif name == 'Grep':
        return f"Searched code: \"{inp.get('pattern', '')}\""
    elif name == 'EnterPlanMode':
        return "Entered plan mode"
    elif name == 'ExitPlanMode':
        return "Exited plan mode"
    elif name == 'AskUserQuestion':
        return None  # Handled separately as Q&A block
    else:
        return f"Used tool: {name}"

def parse_ask_answer(content):
    """Parse the answer text from an AskUserQuestion tool_result."""
    if isinstance(content, list):
        texts = [p.get('text', '') for p in content
                 if isinstance(p, dict) and p.get('type') == 'text']
        content = '\n'.join(texts)

    if not isinstance(content, str):
        return None

    if 'User has answered your questions:' in content:
        pairs = re.findall(r'"([^"]*)"="([^"]*)"', content)
        if pairs:
            return '; '.join(a.strip() for _, a in pairs)

    if "doesn't want to proceed" in content or 'user wants to clarify' in content.lower():
        # User rejected or wants to clarify — extract their message if present
        if 'the user said:' in content.lower():
            idx = content.lower().find('the user said:')
            after = content[idx + len('the user said:'):].strip()
            # Trim system boilerplate
            for marker in ['\n    Questions asked:', '\n\n    This means']:
                end_idx = after.find(marker)
                if end_idx > 0:
                    after = after[:end_idx].strip()
            # Filter out system-generated non-answers
            if after and 'user wants to clarify these questions' not in after.lower():
                return after
        return "[User requested clarification]"

    return None

def format_question_answer(qa):
    """Render a Q&A block as markdown."""
    lines = []
    for q in qa['questions']:
        lines.append(f"> **Question:** {q.get('question', '')}")
        for opt in q.get('options', []):
            label = opt.get('label', '')
            desc = opt.get('description', '')
            lines.append(f"> - **{label}** — {desc}")
    lines.append(">")
    if qa.get('answer'):
        lines.append(f"> **Answer:** {qa['answer']}")
    else:
        lines.append("> **Answer:** [No answer recorded]")
    return '\n'.join(lines)

def find_winning_path(all_records):
    """Find the main conversation branch by walking back from the latest terminal.

    When users retry/rewrite messages, the conversation forms a tree with multiple
    branches. This finds the "winning" branch by:
    1. Finding all terminal nodes (messages with no children)
    2. Picking the one with the latest timestamp
    3. Walking backwards via parentUuid to the root

    Returns a set of UUIDs on the winning path, or None if tree detection fails.
    """
    # Build uuid -> record map
    by_uuid = {r['uuid']: r for r in all_records if r.get('uuid')}

    if not by_uuid:
        return None

    # Find all parent uuids (nodes that have children)
    parent_uuids = {r['parentUuid'] for r in all_records if r.get('parentUuid')}

    # Find terminal nodes (have uuid but aren't anyone's parent)
    terminals = [r for r in all_records
                 if r.get('uuid') and r['uuid'] not in parent_uuids]

    if not terminals:
        return None

    # Sort by timestamp and pick the latest
    terminals.sort(key=lambda r: r.get('timestamp', ''))
    latest_terminal = terminals[-1]

    # Walk backwards from terminal to root
    winning_uuids = set()
    current = latest_terminal.get('uuid')
    while current:
        winning_uuids.add(current)
        rec = by_uuid.get(current)
        if not rec:
            break
        current = rec.get('parentUuid')

    return winning_uuids


def parse_conversation(jsonl_path):
    """Parse a conversation file and extract enriched metadata.

    Returns messages with tool annotations, Q&A blocks, and write contents
    for richer export output.
    """
    all_records = []
    first_user_msg = None
    last_timestamp = None
    project_path = None
    session_id = None

    try:
        with open(jsonl_path, 'r') as f:
            for line in f:
                try:
                    record = json.loads(line)

                    # Extract metadata
                    if 'sessionId' in record:
                        session_id = record['sessionId']
                    if 'cwd' in record:
                        project_path = record['cwd']
                    if 'timestamp' in record:
                        last_timestamp = record['timestamp']

                    all_records.append(record)

                except json.JSONDecodeError:
                    continue
    except Exception:
        return None

    if not all_records:
        return None

    # Find the winning branch (handles retried/rewritten messages)
    winning_uuids = find_winning_path(all_records)

    # Filter to message records on the winning path
    raw_records = []
    for record in all_records:
        if 'message' not in record or record.get('isMeta'):
            continue
        # If we have a winning path, only include records on it
        if winning_uuids and record.get('uuid'):
            if record['uuid'] not in winning_uuids:
                continue
        raw_records.append(record)

    if not raw_records:
        return None

    # Phase 1: Collect AskUserQuestion data and answers
    ask_questions = {}  # tool_use_id -> tool_use input
    ask_answers = {}    # tool_use_id -> parsed answer string

    for record in raw_records:
        msg = record['message']
        content = msg.get('content', [])
        if not isinstance(content, list):
            continue
        for part in content:
            if not isinstance(part, dict):
                continue
            if (part.get('type') == 'tool_use'
                    and part.get('name') == 'AskUserQuestion'):
                ask_questions[part['id']] = part.get('input', {})
            elif (part.get('type') == 'tool_result'
                    and part.get('tool_use_id') in ask_questions):
                answer = parse_ask_answer(part.get('content', ''))
                ask_answers[part['tool_use_id']] = answer

    # Phase 2: Group records into turns
    # Assistant records with the same requestId form one turn.
    # User records are individual turns.
    turns = []
    current_req_id = None
    current_assistant_records = []

    def flush_assistant():
        nonlocal current_req_id, current_assistant_records
        if current_assistant_records:
            turns.append(('assistant', current_assistant_records))
        current_req_id = None
        current_assistant_records = []

    for record in raw_records:
        msg = record['message']
        role = msg.get('role')
        content = msg.get('content')
        if not content:
            continue

        req_id = record.get('requestId', '')

        if role == 'assistant':
            if req_id and req_id == current_req_id:
                current_assistant_records.append(record)
            else:
                flush_assistant()
                current_req_id = req_id
                current_assistant_records = [record]
        elif role == 'user':
            flush_assistant()
            turns.append(('user', [record]))

    flush_assistant()

    # Phase 3: Build enriched messages
    messages = []

    # Track which tool_use_ids are AskUserQuestion answers so we can
    # skip user messages that are purely tool_results for those
    ask_tool_result_ids = set(ask_questions.keys())

    for role, records in turns:
        if role == 'assistant':
            text_parts = []
            tool_annotations = []
            question_answers = []
            write_contents = []
            timestamp = records[0].get('timestamp')

            for rec in records:
                msg = rec['message']
                content = msg.get('content', [])
                if isinstance(content, str):
                    text_parts.append(content)
                    continue
                if not isinstance(content, list):
                    continue
                for part in content:
                    if not isinstance(part, dict):
                        continue
                    if part.get('type') == 'text':
                        text_parts.append(part.get('text', ''))
                    elif part.get('type') == 'tool_use':
                        name = part.get('name', '')
                        # Generate annotation
                        ann = generate_tool_annotation(part)
                        if ann:
                            tool_annotations.append(ann)
                        # Collect AskUserQuestion Q&A
                        if name == 'AskUserQuestion':
                            tool_id = part.get('id', '')
                            q_input = part.get('input', {})
                            question_answers.append({
                                'questions': q_input.get('questions', []),
                                'answer': ask_answers.get(tool_id)
                            })
                        # Collect Write file content
                        if name == 'Write':
                            inp = part.get('input', {})
                            file_content = inp.get('content', '')
                            file_path = inp.get('file_path', '')
                            line_count = (file_content.count('\n') + 1
                                          if file_content else 0)
                            if file_content and line_count <= 200:
                                write_contents.append({
                                    'file_path': file_path,
                                    'content': file_content,
                                    'line_count': line_count
                                })
                    # Skip thinking blocks silently

            text = '\n\n'.join(t for t in text_parts if t.strip())

            # Include message if it has text, annotations, or Q&A
            if (is_meaningful(text) or tool_annotations
                    or question_answers):
                messages.append({
                    'role': 'assistant',
                    'text': text,
                    'timestamp': timestamp,
                    'tool_annotations': tool_annotations,
                    'question_answers': question_answers,
                    'write_contents': write_contents,
                })

        else:  # user
            record = records[0]
            msg = record['message']
            content = msg.get('content')
            timestamp = record.get('timestamp')

            # Check if this user message is purely a tool_result for
            # AskUserQuestion (no meaningful user text). If so, skip it
            # since the Q&A is rendered with the assistant message.
            if isinstance(content, list):
                text_parts = []
                is_only_ask_results = True
                for part in content:
                    if isinstance(part, dict):
                        if part.get('type') == 'text':
                            text_parts.append(part.get('text', ''))
                        elif part.get('type') == 'tool_result':
                            if part.get('tool_use_id') not in ask_tool_result_ids:
                                is_only_ask_results = False
                        else:
                            is_only_ask_results = False
                    else:
                        is_only_ask_results = False
                text = '\n\n'.join(text_parts)
                # If only AskUserQuestion results and no meaningful text,
                # skip this message
                if is_only_ask_results and not is_meaningful(text):
                    continue
            elif isinstance(content, str):
                text = content
            else:
                continue

            if not is_meaningful(text):
                continue

            messages.append({
                'role': 'user',
                'text': text,
                'timestamp': timestamp,
            })

            if not first_user_msg:
                first_user_msg = text.split('\n')[0][:100]

    if not messages:
        return None

    return {
        'path': str(jsonl_path),
        'session_id': session_id or jsonl_path.stem,
        'project': project_path,
        'first_message': first_user_msg or "Unknown",
        'message_count': len(messages),
        'user_messages': sum(1 for m in messages if m['role'] == 'user'),
        'last_timestamp': last_timestamp,
        'messages': messages
    }

def get_all_conversations(project_path=None):
    """Scan and return all conversations, optionally filtered by project."""
    conversations = []

    # Find all .jsonl files
    for project_dir in PROJECTS_DIR.iterdir():
        if not project_dir.is_dir():
            continue

        for jsonl_file in project_dir.glob("*.jsonl"):
            # Skip agent files
            if jsonl_file.stem.startswith('agent-'):
                continue

            conv = parse_conversation(jsonl_file)
            if not conv:
                continue

            # Filter by project if specified
            if project_path and conv['project']:
                conv_project = normalize_path(conv['project'])
                if conv_project != project_path:
                    continue

            conversations.append(conv)

    # Sort by timestamp (most recent first)
    conversations.sort(key=lambda c: c['last_timestamp'] or '', reverse=True)
    return conversations

def list_conversations(project_path=None):
    """List all conversations with metadata."""
    if project_path:
        print(f"Scanning conversations for project: {project_path}\n")
    else:
        print("Scanning conversations in ~/.claude/projects/...\n")

    conversations = get_all_conversations(project_path)

    # Display
    print(f"Found {len(conversations)} conversations\n")
    print("=" * 100)

    for i, conv in enumerate(conversations, 1):
        dt = datetime.fromisoformat(conv['last_timestamp'].replace('Z', '+00:00'))
        project_name = Path(conv['project']).name if conv['project'] else "Unknown"

        print(f"\n{i}. {conv['first_message']}")
        print(f"   Project: {project_name}")
        print(f"   Date: {dt.strftime('%Y-%m-%d %H:%M')}")
        print(f"   Messages: {conv['user_messages']} user, {conv['message_count']} total")
        print(f"   Session: {conv['session_id']}")
        print(f"   Path: {conv['path']}")

def recent_conversations(limit=10, project_path=None):
    """List N most recent conversations."""
    if project_path:
        print(f"Scanning for {limit} most recent conversations in project: {project_path}\n")
    else:
        print(f"Scanning for {limit} most recent conversations...\n")

    conversations = get_all_conversations(project_path)[:limit]

    print(f"Showing {len(conversations)} most recent conversations\n")
    print("=" * 100)

    for i, conv in enumerate(conversations, 1):
        dt = datetime.fromisoformat(conv['last_timestamp'].replace('Z', '+00:00'))
        project_name = Path(conv['project']).name if conv['project'] else "Unknown"

        print(f"\n{i}. {conv['first_message']}")
        print(f"   Project: {project_name}")
        print(f"   Date: {dt.strftime('%Y-%m-%d %H:%M')}")
        print(f"   Messages: {conv['user_messages']} user, {conv['message_count']} total")
        print(f"   Session: {conv['session_id']}")
        print(f"\n   To export: ./find_conversations.py export {conv['session_id']}")

def search_conversations(keyword, project_path=None):
    """Search all conversations for a keyword."""
    if project_path:
        print(f"Searching for '{keyword}' in project: {project_path}\n")
    else:
        print(f"Searching for '{keyword}' in ~/.claude/projects/...\n")

    results = []

    for project_dir in PROJECTS_DIR.iterdir():
        if not project_dir.is_dir():
            continue

        for jsonl_file in project_dir.glob("*.jsonl"):
            if jsonl_file.stem.startswith('agent-'):
                continue

            conv = parse_conversation(jsonl_file)
            if not conv:
                continue

            # Filter by project if specified
            if project_path and conv['project']:
                conv_project = normalize_path(conv['project'])
                if conv_project != project_path:
                    continue

            # Search in messages
            matches = []
            for i, msg in enumerate(conv['messages']):
                if keyword.lower() in msg['text'].lower():
                    # Get context (snippet around match)
                    text = msg['text']
                    idx = text.lower().find(keyword.lower())
                    start = max(0, idx - 50)
                    end = min(len(text), idx + len(keyword) + 50)
                    snippet = "..." + text[start:end] + "..."

                    matches.append({
                        'msg_num': i + 1,
                        'role': msg['role'],
                        'snippet': snippet
                    })

            if matches:
                results.append({
                    'conv': conv,
                    'matches': matches
                })

    # Display results
    if not results:
        print(f"No conversations found containing '{keyword}'")
        return

    print(f"Found {len(results)} conversation(s) with matches\n")
    print("=" * 100)

    for i, result in enumerate(results, 1):
        conv = result['conv']
        dt = datetime.fromisoformat(conv['last_timestamp'].replace('Z', '+00:00'))
        project_name = Path(conv['project']).name if conv['project'] else "Unknown"

        print(f"\n{i}. {conv['first_message']}")
        print(f"   Project: {project_name}")
        print(f"   Date: {dt.strftime('%Y-%m-%d %H:%M')}")
        print(f"   Session: {conv['session_id']}")
        print(f"   Matches: {len(result['matches'])}")

        # Show first few matches
        for match in result['matches'][:3]:
            print(f"   - Msg #{match['msg_num']} ({match['role']}): {match['snippet']}")

        if len(result['matches']) > 3:
            print(f"   ... and {len(result['matches']) - 3} more matches")

def export_conversation(session_id, output_path=None):
    """Export a specific conversation to markdown with TOC."""
    # Find the conversation file (supports prefix matching)
    matches = []
    for project_dir in PROJECTS_DIR.iterdir():
        if not project_dir.is_dir():
            continue
        for jsonl_file in project_dir.glob("*.jsonl"):
            if jsonl_file.stem.startswith('agent-'):
                continue
            if jsonl_file.stem == session_id or jsonl_file.stem.startswith(session_id):
                matches.append(jsonl_file)

    if not matches:
        print(f"Conversation {session_id} not found")
        return

    if len(matches) > 1:
        print(f"Ambiguous prefix '{session_id}', matches {len(matches)} conversations:")
        for m in matches:
            print(f"  {m.stem}")
        print("\nUse a longer prefix to narrow it down.")
        return

    conv_file = matches[0]

    conv = parse_conversation(conv_file)
    if not conv:
        print(f"Failed to parse conversation {session_id}")
        return

    # Generate output path
    if not output_path:
        dt = datetime.fromisoformat(conv['last_timestamp'].replace('Z', '+00:00'))
        safe_title = conv['first_message'][:30].replace('/', '-').replace(' ', '-')
        output_path = f"conversation-{dt.strftime('%Y%m%d')}-{safe_title}.md"

    # Export to markdown with TOC (same format as notes-scroll.md)
    with open(output_path, 'w') as f:
        # Header
        f.write(f"# Conversation: {conv['first_message']}\n\n")
        f.write(f"**Date:** {conv['last_timestamp']}\n")
        f.write(f"**Project:** {conv['project']}\n")
        f.write(f"**Messages:** {conv['message_count']}\n\n")

        # Table of Contents with user messages only
        f.write("## Table of Contents\n\n")

        user_count = 0
        for msg in conv['messages']:
            if msg['role'] == 'user':
                user_count += 1
                # Create preview (first line or first 100 chars)
                first_line = msg['text'].strip().split('\n')[0]
                preview = first_line[:100]
                if len(first_line) > 100:
                    preview += "..."
                f.write(f"{user_count}. [{preview}](#user-{user_count})\n")

        f.write("\n---\n\n")

        # Messages with anchors
        user_count = 0
        for msg in conv['messages']:
            if msg['role'] == 'user':
                user_count += 1
                f.write(f"## <a id=\"user-{user_count}\"></a>User #{user_count}\n\n")
                f.write(f"{msg['text']}\n\n")

            elif msg['role'] == 'assistant':
                # Write text content (may be empty for tool-only turns)
                if msg['text'].strip():
                    f.write(f"**Assistant:**\n\n")
                    f.write(f"{msg['text']}\n\n")

                # Render Q&A blocks (AskUserQuestion + user answer)
                for qa in msg.get('question_answers', []):
                    f.write(format_question_answer(qa))
                    f.write("\n\n")

                # Render tool annotations as italic blockquote lines
                annotations = msg.get('tool_annotations', [])
                write_map = {}
                for wc in msg.get('write_contents', []):
                    write_map[shorten_path(wc['file_path'])] = wc

                if annotations:
                    for ann in annotations:
                        f.write(f"> _{ann}_\n")

                        # If this is a Write annotation, add collapsible
                        # file content block
                        if ann.startswith("Wrote: "):
                            wrote_path = ann[len("Wrote: "):]
                            wc = write_map.get(wrote_path)
                            if wc:
                                # Detect language from file extension
                                ext = Path(wc['file_path']).suffix.lstrip('.')
                                lang = ext if ext else ''
                                f.write(f"> <details><summary>File content ({wc['line_count']} lines)</summary>\n>\n")
                                f.write(f"> ```{lang}\n")
                                for file_line in wc['content'].splitlines():
                                    f.write(f"> {file_line}\n")
                                f.write(f"> ```\n> </details>\n")
                    f.write("\n")

            f.write("---\n\n")

    print(f"Exported to: {output_path}")
    print(f"Table of Contents: {user_count} user messages")

def copy_to_clipboard(text):
    """Copy text to clipboard using platform-specific commands."""
    system = platform.system()

    try:
        if system == 'Darwin':  # macOS
            process = subprocess.Popen(['pbcopy'], stdin=subprocess.PIPE, stderr=subprocess.PIPE)
            _, stderr = process.communicate(text.encode('utf-8'))
            if process.returncode != 0:
                print(f"Error: pbcopy command failed: {stderr.decode('utf-8', errors='ignore')}")
                return False
        elif system == 'Linux':
            # Try Wayland first (wl-copy), then X11 tools (xclip, xsel)
            clipboard_tools = [
                ['wl-copy'],                                    # Wayland
                ['xclip', '-selection', 'clipboard'],           # X11
                ['xsel', '--clipboard', '--input'],             # X11 fallback
            ]

            success = False
            last_error = None

            for tool in clipboard_tools:
                try:
                    process = subprocess.Popen(tool, stdin=subprocess.PIPE, stderr=subprocess.PIPE)
                    _, stderr = process.communicate(text.encode('utf-8'))
                    if process.returncode == 0:
                        success = True
                        break
                    last_error = stderr.decode('utf-8', errors='ignore')
                except FileNotFoundError:
                    continue

            if not success:
                print("Error: No clipboard tool found on Linux")
                print("Please install one of: wl-clipboard (Wayland), xclip, or xsel (X11)")
                if last_error:
                    print(f"Last error: {last_error}")
                return False

        elif system == 'Windows':
            # clip.exe works in both PowerShell and CMD
            # subprocess launches the executable directly, not through a shell
            process = subprocess.Popen(['clip'], stdin=subprocess.PIPE, stderr=subprocess.PIPE)
            _, stderr = process.communicate(text.encode('utf-8'))
            if process.returncode != 0:
                print(f"Error: clip command failed: {stderr.decode('utf-8', errors='ignore')}")
                return False
        else:
            print(f"Unsupported platform: {system}")
            return False
        return True
    except Exception as e:
        print(f"Error copying to clipboard: {e}")
        return False

def copy_last_message(project_path=None):
    """Copy the last assistant message from the most recent conversation to clipboard."""
    if project_path:
        print(f"Finding most recent conversation in project: {project_path}\n")
    else:
        print("Finding most recent conversation...\n")

    conversations = get_all_conversations(project_path)

    if not conversations:
        print("No conversations found")
        return

    # Get the most recent conversation
    conv = conversations[0]

    # Find the last assistant message
    last_assistant_msg = None
    for msg in reversed(conv['messages']):
        if msg['role'] == 'assistant':
            last_assistant_msg = msg['text']
            break

    if not last_assistant_msg:
        print("No assistant messages found in the most recent conversation")
        return

    # Copy to clipboard
    if copy_to_clipboard(last_assistant_msg):
        dt = datetime.fromisoformat(conv['last_timestamp'].replace('Z', '+00:00'))
        project_name = Path(conv['project']).name if conv['project'] else "Unknown"

        print("✓ Copied last assistant message to clipboard!")
        print(f"\nFrom conversation: {conv['first_message']}")
        print(f"Project: {project_name}")
        print(f"Date: {dt.strftime('%Y-%m-%d %H:%M')}")
        print(f"\nMessage length: {len(last_assistant_msg)} characters")
        print(f"Preview: {last_assistant_msg[:150]}...")
    else:
        print("Failed to copy to clipboard")

def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)

    command = sys.argv[1]

    # Parse --project flag
    project_path = None
    args = sys.argv[2:]
    if '--project' in args:
        idx = args.index('--project')
        if idx + 1 < len(args):
            project_path = normalize_path(args[idx + 1])
            # Remove --project and its value from args
            args = args[:idx] + args[idx+2:]
        else:
            print("Error: --project requires a path argument")
            sys.exit(1)

    if command == "list":
        list_conversations(project_path)
    elif command == "recent":
        limit = int(args[0]) if args and args[0].isdigit() else 10
        recent_conversations(limit, project_path)
    elif command == "search":
        if not args or args[0].startswith('--'):
            print("Usage: ./find_conversations.py search KEYWORD [--project PATH]")
            sys.exit(1)
        search_conversations(args[0], project_path)
    elif command == "export":
        if not args:
            print("Usage: ./find_conversations.py export SESSION_ID [OUTPUT_PATH]")
            sys.exit(1)
        session_id = args[0]
        output = args[1] if len(args) > 1 and not args[1].startswith('--') else None
        export_conversation(session_id, output)
    elif command == "copy-last":
        copy_last_message(project_path)
    else:
        print(f"Unknown command: {command}")
        print(__doc__)
        sys.exit(1)

if __name__ == "__main__":
    main()
