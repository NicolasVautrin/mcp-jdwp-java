#!/usr/bin/env python3
"""Test inspection des champs d'objets"""

import asyncio
import re
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

async def test_object_fields():
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

            # Thread 15 = http-nio-8080-exec-1 avec le breakpoint
            thread_id = 15

            print("=== VARIABLES LOCALES FRAME 0 ===")
            result = await session.call_tool("jdwp_get_locals", {
                "threadId": thread_id,
                "frameIndex": 0
            })
            locals_text = result.content[0].text
            print(locals_text)

            # Extraire l'ID de l'objet request
            # Format: "request (com.axelor.rpc.Request) = Object#26886 (com.axelor.rpc.Request)"
            match = re.search(r'Object#(\d+)', locals_text)
            if match:
                object_id = int(match.group(1))
                print(f"\n=== CHAMPS DE L'OBJET request (ID {object_id}) ===")

                result = await session.call_tool("jdwp_get_fields", {
                    "objectId": object_id
                })
                print(result.content[0].text)

                # Chercher d'autres objets dans les fields
                fields_text = result.content[0].text
                sub_objects = re.findall(r'Object#(\d+)', fields_text)

                if sub_objects:
                    # Inspecter le premier sous-objet
                    sub_id = int(sub_objects[0])
                    print(f"\n=== CHAMPS DU SOUS-OBJET (ID {sub_id}) ===")
                    result = await session.call_tool("jdwp_get_fields", {
                        "objectId": sub_id
                    })
                    print(result.content[0].text)

            # Deconnexion
            await session.call_tool("jdwp_disconnect", {})

if __name__ == "__main__":
    asyncio.run(test_object_fields())
