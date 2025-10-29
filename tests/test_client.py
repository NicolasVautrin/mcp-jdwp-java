#!/usr/bin/env python3
"""Test client for MCP JDWP server"""

import asyncio
import json
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

async def test_server():
    # Configuration du serveur
    server_params = StdioServerParameters(
        command="java",
        args=[
            "-jar",
            "C:/Users/nicolasv/MCP_servers/mcp-jdwp-java/build/libs/mcp-jdwp-java-1.0.0.jar"
        ]
    )

    print("Demarrage du serveur MCP JDWP...")

    async with stdio_client(server_params) as (read, write):
        async with ClientSession(read, write) as session:
            # Initialiser la session
            await session.initialize()
            print("OK - Serveur initialise!")

            # Lister les outils disponibles
            tools = await session.list_tools()
            print(f"\n{len(tools.tools)} outils disponibles:")
            for tool in tools.tools:
                print(f"  - {tool.name}: {tool.description}")

            # Test 1: Connexion JDWP
            print("\nTest connexion JDWP (localhost:55005)...")
            try:
                result = await session.call_tool("jdwp_connect", {
                    "host": "localhost",
                    "port": 55005
                })
                print(f"OK - {result.content[0].text}")
            except Exception as e:
                print(f"ERREUR: {e}")
                return

            # Test 2: Version JVM
            print("\nRecuperation version JVM...")
            try:
                result = await session.call_tool("jdwp_get_version", {})
                print(f"OK - {result.content[0].text}")
            except Exception as e:
                print(f"ERREUR: {e}")

            # Test 3: Liste des threads
            print("\nRecuperation des threads...")
            try:
                result = await session.call_tool("jdwp_get_threads", {})
                text = result.content[0].text
                print(f"OK - {text[:500]}...")  # Premiers 500 chars
            except Exception as e:
                print(f"ERREUR: {e}")

            # Test 4: Deconnexion
            print("\nDeconnexion...")
            try:
                result = await session.call_tool("jdwp_disconnect", {})
                print(f"OK - {result.content[0].text}")
            except Exception as e:
                print(f"ERREUR: {e}")

            print("\nTests termines!")

if __name__ == "__main__":
    asyncio.run(test_server())
