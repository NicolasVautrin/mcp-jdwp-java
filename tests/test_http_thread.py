#!/usr/bin/env python3
"""Test du thread HTTP avec breakpoint"""

import asyncio
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

async def test_http_thread():
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

            # Thread 15 = http-nio-8080-exec-1 avec 93 frames
            thread_id = 15

            print("=== STACK DU THREAD HTTP (ID 15) ===")
            result = await session.call_tool("jdwp_get_stack", {
                "threadId": thread_id
            })
            print(result.content[0].text)

            print("\n=== VARIABLES LOCALES FRAME 0 ===")
            result = await session.call_tool("jdwp_get_locals", {
                "threadId": thread_id,
                "frameIndex": 0
            })
            print(result.content[0].text)

            print("\n=== VARIABLES LOCALES FRAME 1 ===")
            result = await session.call_tool("jdwp_get_locals", {
                "threadId": thread_id,
                "frameIndex": 1
            })
            print(result.content[0].text)

            # Deconnexion
            await session.call_tool("jdwp_disconnect", {})

if __name__ == "__main__":
    asyncio.run(test_http_thread())
