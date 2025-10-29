#!/usr/bin/env python3
"""Test des vues intelligentes des collections"""

import asyncio
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

async def test_collections():
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

            # Charger le cache
            print("=== CHARGEMENT DU CACHE ===")
            result = await session.call_tool("jdwp_get_locals", {
                "threadId": thread_id,
                "frameIndex": 0
            })
            print(result.content[0].text)

            # Request fields
            print("\n=== REQUEST FIELDS ===")
            result = await session.call_tool("jdwp_get_fields", {
                "objectId": 26886  # request
            })
            print(result.content[0].text)

            # Test ArrayList (sortBy)
            print("\n=== ARRAYLIST SORTBY (ID 26935) - Vue intelligente ===")
            result = await session.call_tool("jdwp_get_fields", {
                "objectId": 26935
            })
            print(result.content[0].text)

            # Test LinkedHashMap (data)
            print("\n=== LINKEDHASHMAP DATA (ID 26936) - Vue intelligente ===")
            result = await session.call_tool("jdwp_get_fields", {
                "objectId": 26936
            })
            print(result.content[0].text)

            # Test ArrayList (fields)
            print("\n=== ARRAYLIST FIELDS (ID 26937) - Vue intelligente ===")
            result = await session.call_tool("jdwp_get_fields", {
                "objectId": 26937
            })
            print(result.content[0].text)

            await session.call_tool("jdwp_disconnect", {})

if __name__ == "__main__":
    asyncio.run(test_collections())
