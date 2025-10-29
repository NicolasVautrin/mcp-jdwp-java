#!/usr/bin/env python3
"""Test de l'invocation de methodes (scripting)"""

import asyncio
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

async def test_invoke():
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

            # Test 1: Appeler toString() sur request
            print("\n=== TEST 1: request.toString() ===")
            result = await session.call_tool("jdwp_invoke_method", {
                "threadId": thread_id,
                "objectId": 26886,  # request
                "methodName": "toString"
            })
            print(result.content[0].text)

            # Test 2: Appeler getModel() sur request
            print("\n=== TEST 2: request.getModel() ===")
            result = await session.call_tool("jdwp_invoke_method", {
                "threadId": thread_id,
                "objectId": 26886,
                "methodName": "getModel"
            })
            print(result.content[0].text)

            # Test 3: Appeler getLimit() sur request
            print("\n=== TEST 3: request.getLimit() ===")
            result = await session.call_tool("jdwp_invoke_method", {
                "threadId": thread_id,
                "objectId": 26886,
                "methodName": "getLimit"
            })
            print(result.content[0].text)

            # Test 4: Appeler getData() sur request
            print("\n=== TEST 4: request.getData() ===")
            result = await session.call_tool("jdwp_invoke_method", {
                "threadId": thread_id,
                "objectId": 26886,
                "methodName": "getData"
            })
            print(result.content[0].text)

            await session.call_tool("jdwp_disconnect", {})

if __name__ == "__main__":
    asyncio.run(test_invoke())
