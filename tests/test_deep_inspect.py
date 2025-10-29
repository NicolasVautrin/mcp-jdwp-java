#!/usr/bin/env python3
"""Inspection profonde des objets - navigation dans request.data"""

import asyncio
import re
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

async def inspect_object(session, obj_id, depth=0, max_depth=3):
    """Inspecte recursivement un objet"""
    if depth > max_depth:
        return

    indent = "  " * depth
    print(f"{indent}=== OBJECT #{obj_id} ===")

    result = await session.call_tool("jdwp_get_fields", {
        "objectId": obj_id
    })
    fields_text = result.content[0].text
    print(fields_text)

async def test_deep():
    server_params = StdioServerParameters(
        command="java",
        args=[
            "-jar",
            "C:/Users/nicolasv/MCP_servers/mcp-jdwp-java/build/libs/mcp-jdwp-java-1.0.0.jar"
        ]
    )

    async with stdio_client(server_params) as (read, write):
        async with ClientSession(read, write) as session:
            await session.initialize()

            # Connexion
            await session.call_tool("jdwp_connect", {
                "host": "localhost",
                "port": 55005
            })

            thread_id = 15

            # 1. Get locals
            print("=== FRAME 0: Variables locales ===")
            result = await session.call_tool("jdwp_get_locals", {
                "threadId": thread_id,
                "frameIndex": 0
            })
            print(result.content[0].text)

            # 2. Inspect request object
            print("\n=== REQUEST OBJECT ===")
            result = await session.call_tool("jdwp_get_fields", {
                "objectId": 26886  # request
            })
            request_fields = result.content[0].text
            print(request_fields)

            # 3. Trouver l'ID de data (LinkedHashMap)
            match = re.search(r'java\.util\.Map data = Object#(\d+)', request_fields)
            if match:
                data_id = int(match.group(1))
                print(f"\n=== REQUEST.DATA (LinkedHashMap ID {data_id}) ===")
                result = await session.call_tool("jdwp_get_fields", {
                    "objectId": data_id
                })
                print(result.content[0].text)

            # 4. Trouver l'ID de sortBy (ArrayList)
            match = re.search(r'java\.util\.List sortBy = Object#(\d+)', request_fields)
            if match:
                sortby_id = int(match.group(1))
                print(f"\n=== REQUEST.SORTBY (ArrayList ID {sortby_id}) ===")
                result = await session.call_tool("jdwp_get_fields", {
                    "objectId": sortby_id
                })
                print(result.content[0].text)

            # 5. Trouver l'ID de fields (ArrayList)
            match = re.search(r'java\.util\.List fields = Object#(\d+)', request_fields)
            if match:
                fields_id = int(match.group(1))
                print(f"\n=== REQUEST.FIELDS (ArrayList ID {fields_id}) ===")
                result = await session.call_tool("jdwp_get_fields", {
                    "objectId": fields_id
                })
                print(result.content[0].text)

            # Deconnexion
            await session.call_tool("jdwp_disconnect", {})

if __name__ == "__main__":
    asyncio.run(test_deep())
