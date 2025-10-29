#!/usr/bin/env python3
"""Descendre dans le tableau elementData pour voir les elements de l'ArrayList"""

import asyncio
import re
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

async def test_array():
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

            await session.call_tool("jdwp_connect", {
                "host": "localhost",
                "port": 55005
            })

            thread_id = 15

            # 0. IMPORTANT: Peupler le cache avec get_locals d'abord
            print("=== CHARGEMENT DU CACHE (get_locals) ===")
            result = await session.call_tool("jdwp_get_locals", {
                "threadId": thread_id,
                "frameIndex": 0
            })
            print(result.content[0].text)

            # 1. Get request object fields
            print("=== REQUEST FIELDS ===")
            result = await session.call_tool("jdwp_get_fields", {
                "objectId": 26886  # request
            })
            print(result.content[0].text)

            # 2. Get sortBy ArrayList
            print("\n=== SORTBY ARRAYLIST (ID 26935) ===")
            result = await session.call_tool("jdwp_get_fields", {
                "objectId": 26935  # sortBy ArrayList
            })
            sortby_fields = result.content[0].text
            print(sortby_fields)

            # 3. Get elementData array
            match = re.search(r'java\.lang\.Object\[\] elementData = (?:Array|Object)#(\d+)', sortby_fields)
            if match:
                array_id = int(match.group(1))
                print(f"\n=== ELEMENTDATA ARRAY (ID {array_id}) ===")
                result = await session.call_tool("jdwp_get_fields", {
                    "objectId": array_id
                })
                array_content = result.content[0].text
                print(array_content)

                # 4. Get first element in array
                objects = re.findall(r'Object#(\d+)', array_content)
                if objects:
                    elem_id = int(objects[0])
                    print(f"\n=== PREMIER ELEMENT DU TABLEAU (ID {elem_id}) ===")
                    result = await session.call_tool("jdwp_get_fields", {
                        "objectId": elem_id
                    })
                    print(result.content[0].text)

            await session.call_tool("jdwp_disconnect", {})

if __name__ == "__main__":
    asyncio.run(test_array())
