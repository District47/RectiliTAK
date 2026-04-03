#!/usr/bin/env python3
"""
Test peer for RectiliTAK — run on your Mac to test direct messaging.
Connects to the same Reticulum TCP bootstrap as the plugin.
Prints its address, listens for DMs, and echoes them back.
"""

import RNS
import json
import time
import sys
import os
import tempfile

APP_NAME = "atak_chat"

def main():
    # Use a temp config dir so we don't interfere with any system RNS
    config_dir = os.path.join(tempfile.gettempdir(), "rectilitak_test")
    os.makedirs(config_dir, exist_ok=True)

    config_file = os.path.join(config_dir, "config")
    if not os.path.exists(config_file):
        with open(config_file, "w") as f:
            f.write("""[reticulum]
enable_transport = false
share_instance   = false

[interfaces]

  [[Default Interface]]
  type    = AutoInterface
  enabled = true

  [[TCP Bootstrap]]
  type             = TCPClientInterface
  enabled          = true
  target_host      = rns.beleth.net
  target_port      = 4242
""")
        print("Config written to", config_file)

    # Start Reticulum
    reticulum = RNS.Reticulum(config_dir)

    # Create or load identity
    id_path = os.path.join(config_dir, "test_identity")
    if os.path.exists(id_path):
        identity = RNS.Identity.from_file(id_path)
        print("Loaded existing identity")
    else:
        identity = RNS.Identity()
        identity.to_file(id_path)
        print("Created new identity")

    # Create DM destination (same aspect as RectiliTAK plugin)
    dm_dest = RNS.Destination(
        identity,
        RNS.Destination.IN,
        RNS.Destination.SINGLE,
        APP_NAME,
        "dm"
    )

    my_address = RNS.prettyhexrep(dm_dest.hash)
    print()
    print("=" * 60)
    print("TEST PEER READY")
    print("=" * 60)
    print()
    print("  My address: {}".format(my_address))
    print()
    print("  Paste this address into RectiliTAK's Direct mode")
    print("  to send a message to this peer.")
    print()
    print("=" * 60)
    print()
    print("Waiting for messages... (Ctrl+C to quit)")
    print()

    # Announce ourselves
    app_data = json.dumps({
        "callsign": "TEST-PEER",
        "uid": "test-peer-001"
    }).encode("utf-8")
    dm_dest.announce(app_data=app_data)
    print("Announced on network")

    # Handle incoming DMs
    def on_packet(message, packet):
        try:
            data = json.loads(message.decode("utf-8"))
            sender = data.get("from", "Unknown")
            body = data.get("body", "")
            sender_hash = data.get("sender_hash", "unknown")
            ts = data.get("ts", int(time.time()))

            print()
            print("[DM from {} ({})]".format(sender, sender_hash[:16]))
            print("  {}".format(body))
            print()

            # Echo back
            if sender_hash != "unknown":
                try:
                    echo_body = "Echo: {}".format(body)
                    dest_bytes = bytes.fromhex(sender_hash.replace(" ", "").replace("<", "").replace(">", ""))

                    # Request path if needed
                    if not RNS.Transport.has_path(dest_bytes):
                        print("  Requesting path to sender...")
                        RNS.Transport.request_path(dest_bytes)
                        timeout = 10
                        while not RNS.Transport.has_path(dest_bytes) and timeout > 0:
                            time.sleep(0.5)
                            timeout -= 0.5

                    remote_identity = RNS.Identity.recall(dest_bytes)
                    if remote_identity:
                        remote_dest = RNS.Destination(
                            remote_identity,
                            RNS.Destination.OUT,
                            RNS.Destination.SINGLE,
                            APP_NAME,
                            "dm"
                        )
                        payload = json.dumps({
                            "from": "TEST-PEER",
                            "uid": "test-peer-001",
                            "body": echo_body,
                            "ts": int(time.time()),
                            "sender_hash": my_address
                        }).encode("utf-8")
                        packet = RNS.Packet(remote_dest, payload)
                        packet.send()
                        print("  Echoed back: {}".format(echo_body))
                    else:
                        print("  Could not resolve sender identity for echo")
                except Exception as e:
                    print("  Echo failed: {}".format(e))

        except Exception as e:
            print("  Parse error: {}".format(e))

    dm_dest.set_packet_callback(on_packet)

    # Also listen for announces from other peers
    def announce_handler(destination_hash, announced_identity, app_data):
        try:
            if app_data:
                data = json.loads(app_data.decode("utf-8"))
                callsign = data.get("callsign", "?")
                peer_hash = RNS.prettyhexrep(destination_hash)
                print("Peer announced: {} ({})".format(callsign, peer_hash[:16]))
        except:
            pass

    RNS.Transport.register_announce_handler(announce_handler)

    # Keep running
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        print("\nShutting down...")
        reticulum.exit_handler()

if __name__ == "__main__":
    main()
