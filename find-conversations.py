#!/usr/bin/env python3
"""
Find and search through Claude Code conversations.

Usage:
    ./find-conversations.py list [--project PATH]           # List all conversations
    ./find-conversations.py recent [N] [--project PATH]     # List N most recent (default 10)
    ./find-conversations.py search "scroll" [--project PATH]  # Search for keyword
    ./find-conversations.py export SESSION_ID               # Export specific conversation

Options:
    --project PATH    Filter by project path (e.g., --project ~/myproject or --project .)
"""

import json
import sys
import os
from pathlib import Path
from datetime import datetime
from collections import defaultdict

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

def parse_conversation(jsonl_path):
    """Parse a conversation file and extract metadata."""
    messages = []
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

                    if 'message' not in record or record.get('isMeta'):
                        continue

                    msg = record['message']
                    role = msg.get('role')
                    content = msg.get('content')

                    if not content:
                        continue

                    # Handle different content formats
                    if isinstance(content, str):
                        text = content
                    elif isinstance(content, list):
                        text_parts = []
                        for part in content:
                            if isinstance(part, dict) and part.get('type') == 'text':
                                text_parts.append(part.get('text', ''))
                        text = '\n\n'.join(text_parts)
                    else:
                        continue

                    if not is_meaningful(text):
                        continue

                    messages.append({
                        'role': role,
                        'text': text,
                        'timestamp': record.get('timestamp')
                    })

                    # Capture first user message as title
                    if role == 'user' and not first_user_msg:
                        first_user_msg = text.split('\n')[0][:100]

                except json.JSONDecodeError:
                    continue
    except Exception as e:
        return None

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
        print(f"\n   To export: ./find-conversations.py export {conv['session_id']}")

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
    # Find the conversation file
    conv_file = None
    for project_dir in PROJECTS_DIR.iterdir():
        if not project_dir.is_dir():
            continue
        for jsonl_file in project_dir.glob(f"{session_id}.jsonl"):
            conv_file = jsonl_file
            break
        if conv_file:
            break

    if not conv_file:
        print(f"Conversation {session_id} not found")
        return

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
                f.write(f"**Assistant:**\n\n")
                f.write(f"{msg['text']}\n\n")
            f.write("---\n\n")

    print(f"Exported to: {output_path}")
    print(f"Table of Contents: {user_count} user messages")

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
            print("Usage: ./find-conversations.py search KEYWORD [--project PATH]")
            sys.exit(1)
        search_conversations(args[0], project_path)
    elif command == "export":
        if not args:
            print("Usage: ./find-conversations.py export SESSION_ID [OUTPUT_PATH]")
            sys.exit(1)
        session_id = args[0]
        output = args[1] if len(args) > 1 and not args[1].startswith('--') else None
        export_conversation(session_id, output)
    else:
        print(f"Unknown command: {command}")
        print(__doc__)
        sys.exit(1)

if __name__ == "__main__":
    main()
