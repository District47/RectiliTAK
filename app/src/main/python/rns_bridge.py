#!/usr/bin/env python3
"""
rns_bridge.py
ATAK Reticulum Chat Bridge

Runs as a subprocess managed by RNSBridgeService.
Exposes a localhost TCP server on port 17000.
Accepts JSON commands from the Java layer.
Emits JSON events to all connected Java clients.
"""

import RNS
import json
import socket
import threading
import time
import argparse
import os

APP_NAME    = "atak_chat"
BRIDGE_PORT = 17000
IDENTITY_FILE = "atak_identity"
ANNOUNCE_INTERVAL = 300  # seconds



class ATAKChatBridge:

    def __init__(self, callsign, uid, config_path=None, data_dir=None):
        self.callsign = callsign
        self.uid      = uid
        self.peers    = {}   # hash -> callsign
        self.clients  = []   # connected Java sockets
        self._lock    = threading.Lock()

        # Initialise Reticulum
        # Override sys.exit to prevent RNS from killing the JVM on interface errors
        import sys
        _real_exit = sys.exit
        def _safe_exit(code=0):
            raise SystemExit(code)
        sys.exit = _safe_exit
        try:
            self.reticulum = RNS.Reticulum(config_path)
        finally:
            sys.exit = _real_exit

        # Load or create persistent identity
        if data_dir:
            id_path = os.path.join(data_dir, IDENTITY_FILE)
        else:
            id_path = os.path.join(RNS.Reticulum.configdir, IDENTITY_FILE)

        if os.path.exists(id_path):
            self.identity = RNS.Identity.from_file(id_path)
            RNS.log("Loaded existing RNS identity")
        else:
            self.identity = RNS.Identity()
            self.identity.to_file(id_path)
            RNS.log("Created new RNS identity")

        # SINGLE destination for presence announcements and receiving DMs
        self.dm_destination = RNS.Destination(
            self.identity,
            RNS.Destination.IN,
            RNS.Destination.SINGLE,
            APP_NAME,
            "dm"
        )
        self.dm_destination.set_packet_callback(self._on_dm_packet)

        # Register announce handler so we learn about other peers
        RNS.Transport.register_announce_handler(self._announce_handler)

        self.my_address = RNS.prettyhexrep(self.dm_destination.hash)
        RNS.log("My address: {}".format(self.my_address))

        RNS.log("Bridge ready -- callsign={}, uid={}, address={}".format(
            callsign, uid, self.my_address))

    # ------------------------------------------------------------------
    # Announce handler — learn about remote peers
    # ------------------------------------------------------------------

    def _announce_handler(self, destination_hash, announced_identity, app_data):
        try:
            if app_data:
                data = json.loads(app_data.decode("utf-8"))
                callsign = data.get("callsign", "Unknown")
                peer_hash = RNS.prettyhexrep(destination_hash)
                if peer_hash not in self.peers:
                    self.peers[peer_hash] = callsign
                    self._emit({
                        "event":    "peer_appeared",
                        "callsign": callsign,
                        "hash":     peer_hash
                    })
                    RNS.log("Discovered peer: {} ({})".format(callsign, peer_hash))
        except Exception as e:
            RNS.log("Announce handler error: {}".format(e), RNS.LOG_WARNING)


    # ------------------------------------------------------------------
    # Packet receive — direct messages
    # ------------------------------------------------------------------

    def _on_dm_packet(self, message, packet):
        try:
            data = json.loads(message.decode("utf-8"))
            callsign = data.get("from", "Unknown")
            sender_hash = data.get("sender_hash", "unknown")

            if sender_hash != "unknown" and sender_hash not in self.peers:
                self.peers[sender_hash] = callsign

            msg_type = data.get("type", "text")

            if msg_type == "cot_location":
                # Forward location data to Java for map injection
                event = dict(data)
                event["event"] = "location"
                event["sender_hash"] = sender_hash
                self._emit(event)
            else:
                event = {
                    "event": "message",
                    "room":  "Direct",
                    "from":  callsign,
                    "body":  data.get("body", ""),
                    "ts":    data.get("ts", int(time.time())),
                    "sender_hash": sender_hash
                }
                if "group_id" in data:
                    event["group_id"] = data["group_id"]
                    event["group_name"] = data.get("group_name", "")
                    event["room"] = "Group"
                self._emit(event)

        except Exception as e:
            RNS.log("DM packet parse error: {}".format(e), RNS.LOG_ERROR)


    # ------------------------------------------------------------------
    # Announce
    # ------------------------------------------------------------------

    def _announce(self):
        app_data = json.dumps({
            "callsign": self.callsign,
            "uid":      self.uid
        }).encode("utf-8")
        self.dm_destination.announce(app_data=app_data)
        RNS.log("Announced presence")

    def _announce_loop(self):
        while True:
            time.sleep(ANNOUNCE_INTERVAL)
            try:
                self._announce()
            except Exception as e:
                RNS.log("Announce error: {}".format(e), RNS.LOG_WARNING)

    # ------------------------------------------------------------------
    # Emit to Java clients
    # ------------------------------------------------------------------

    def _emit(self, event):
        line = json.dumps(event) + "\n"
        encoded = line.encode("utf-8")
        with self._lock:
            dead = []
            for client in self.clients:
                try:
                    client.sendall(encoded)
                except Exception:
                    dead.append(client)
            for d in dead:
                self.clients.remove(d)

    # ------------------------------------------------------------------
    # Bridge TCP server
    # ------------------------------------------------------------------

    def run(self):
        srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.bind(("127.0.0.1", BRIDGE_PORT))
        srv.listen(5)
        RNS.log("Bridge TCP server listening on port {}".format(BRIDGE_PORT))

        # Initial announce
        self._announce()

        # Periodic re-announce
        threading.Thread(
            target=self._announce_loop, daemon=True
        ).start()

        # Signal ready to any already-waiting Java client
        self._emit({"event": "ready", "address": self.my_address})

        while True:
            conn, addr = srv.accept()
            RNS.log("Java client connected from {}".format(addr))
            with self._lock:
                self.clients.append(conn)
            # Send ready + address to newly connected client
            try:
                ready_msg = json.dumps({
                    "event": "ready",
                    "address": self.my_address
                }) + "\n"
                conn.sendall(ready_msg.encode("utf-8"))
            except Exception:
                pass
            threading.Thread(
                target=self._handle_client,
                args=(conn,),
                daemon=True
            ).start()

    def _handle_client(self, conn):
        buf = ""
        try:
            while True:
                chunk = conn.recv(4096).decode("utf-8")
                if not chunk:
                    break
                buf += chunk
                while "\n" in buf:
                    line, buf = buf.split("\n", 1)
                    line = line.strip()
                    if line:
                        try:
                            self._handle_command(json.loads(line))
                        except json.JSONDecodeError as e:
                            RNS.log("Bad JSON from client: {}".format(e),
                                    RNS.LOG_WARNING)
        except Exception as e:
            RNS.log("Client handler error: {}".format(e), RNS.LOG_WARNING)
        finally:
            with self._lock:
                if conn in self.clients:
                    self.clients.remove(conn)

    def send_to_address(self, dest_hash_hex, body, group_id=None, group_name=None, extra_fields=None):
        """Send a DM to a specific address, optionally tagged with group info or extra data."""
        try:
            clean = dest_hash_hex.strip().replace(" ", "").replace("<", "").replace(">", "")
            dest_hash = bytes.fromhex(clean)

            if len(dest_hash) != RNS.Reticulum.TRUNCATED_HASHLENGTH // 8:
                return False

            if not RNS.Transport.has_path(dest_hash):
                RNS.Transport.request_path(dest_hash)
                timeout = 10
                while not RNS.Transport.has_path(dest_hash) and timeout > 0:
                    time.sleep(0.5)
                    timeout -= 0.5
                if not RNS.Transport.has_path(dest_hash):
                    return False

            remote_identity = RNS.Identity.recall(dest_hash)
            if remote_identity is None:
                return False

            remote_dest = RNS.Destination(
                remote_identity,
                RNS.Destination.OUT,
                RNS.Destination.SINGLE,
                APP_NAME,
                "dm"
            )

            payload_dict = {
                "from": self.callsign,
                "uid":  self.uid,
                "body": body,
                "ts":   int(time.time()),
                "sender_hash": self.my_address
            }
            if group_id:
                payload_dict["group_id"] = group_id
            if group_name:
                payload_dict["group_name"] = group_name
            if extra_fields:
                payload_dict.update(extra_fields)

            packet = RNS.Packet(remote_dest, json.dumps(payload_dict).encode("utf-8"))
            packet.send()
            return True

        except Exception as e:
            RNS.log("send_to_address error: {}".format(e), RNS.LOG_ERROR)
            return False

    def _handle_command(self, cmd):
        c = cmd.get("cmd")
        if c == "send_direct":
            dest = cmd.get("dest", "")
            body = cmd.get("body", "")
            def do_send():
                ok = self.send_to_address(dest, body)
                if ok:
                    self._emit({"event": "status", "body": "Message sent"})
                else:
                    self._emit({"event": "error", "body": "Could not reach {}".format(dest[:16])})
            threading.Thread(target=do_send, daemon=True).start()
        elif c == "send_group":
            members = cmd.get("members", [])
            body = cmd.get("body", "")
            group_id = cmd.get("group_id", "")
            group_name = cmd.get("group_name", "")
            def do_group_send():
                sent = 0
                failed = 0
                for addr in members:
                    ok = self.send_to_address(addr, body, group_id=group_id, group_name=group_name)
                    if ok:
                        sent += 1
                    else:
                        failed += 1
                status = "Sent to {}/{} members".format(sent, sent + failed)
                if failed > 0:
                    status += " ({} unreachable)".format(failed)
                self._emit({"event": "status", "body": status})
            threading.Thread(target=do_group_send, daemon=True).start()
        elif c == "send_location":
            dest = cmd.get("dest", "")
            members = cmd.get("members", [])
            location_data = cmd.get("location", {})
            group_id = cmd.get("group_id", "")
            group_name = cmd.get("group_name", "")
            extra = {"type": "cot_location"}
            extra.update(location_data)
            def do_location_send():
                targets = members if members else [dest] if dest else []
                sent = 0
                failed = 0
                for addr in targets:
                    ok = self.send_to_address(addr, "Shared location", group_id=group_id, group_name=group_name, extra_fields=extra)
                    if ok:
                        sent += 1
                    else:
                        failed += 1
                if sent > 0:
                    self._emit({"event": "status", "body": "Location shared with {}/{} recipient(s)".format(sent, sent + failed)})
                else:
                    self._emit({"event": "error", "body": "Could not share location"})
            threading.Thread(target=do_location_send, daemon=True).start()
        elif c == "set_identity":
            self.callsign = cmd.get("callsign", self.callsign)
            self.uid      = cmd.get("uid", self.uid)
            try:
                self._announce()
            except Exception as e:
                RNS.log("Announce after identity set failed: {}".format(e),
                        RNS.LOG_WARNING)
        elif c == "get_peers":
            self._emit({"event": "peers", "peers": self.peers})
        elif c == "get_address":
            self._emit({"event": "address", "address": self.my_address})
        else:
            RNS.log("Unknown command: {}".format(c), RNS.LOG_WARNING)


# ------------------------------------------------------------------
# Entry point — called from Chaquopy or command line
# ------------------------------------------------------------------

def main(callsign="UNKNOWN", uid="UID-0000", config=None, data_dir=None):
    """Callable entry point for Chaquopy invocation."""
    bridge = ATAKChatBridge(
        callsign=callsign,
        uid=uid,
        config_path=config,
        data_dir=data_dir
    )
    bridge.run()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--callsign", default="UNKNOWN")
    parser.add_argument("--uid",      default="UID-0000")
    parser.add_argument("--config",   default=None)
    parser.add_argument("--data-dir", default=None)
    args = parser.parse_args()

    main(
        callsign=args.callsign,
        uid=args.uid,
        config=args.config,
        data_dir=args.data_dir
    )
