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

ROOMS = ["All Chat", "Team", "Command"]


class ATAKChatBridge:

    def __init__(self, callsign, uid, config_path=None, data_dir=None):
        self.callsign = callsign
        self.uid      = uid
        self.peers    = {}   # hash -> callsign
        self.clients  = []   # connected Java sockets
        self._lock    = threading.Lock()

        # Initialise Reticulum
        self.reticulum = RNS.Reticulum(config_path)

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

        # SINGLE destination for announcing our presence
        self.announce_destination = RNS.Destination(
            self.identity,
            RNS.Destination.IN,
            RNS.Destination.SINGLE,
            APP_NAME,
            "presence"
        )
        RNS.log("Announce destination: {}".format(
            RNS.prettyhexrep(self.announce_destination.hash)))

        # PLAIN destinations for broadcast chat rooms (no identity needed)
        self.room_destinations = {}
        for room in ROOMS:
            self._setup_room(room)

        RNS.log("Bridge ready -- callsign={}, uid={}".format(callsign, uid))

    # ------------------------------------------------------------------
    # Room setup
    # ------------------------------------------------------------------

    def _room_aspect(self, room_name):
        return room_name.lower().replace(" ", "_")

    def _setup_room(self, room_name):
        dest = RNS.Destination(
            None,
            RNS.Destination.IN,
            RNS.Destination.PLAIN,
            APP_NAME,
            self._room_aspect(room_name)
        )
        dest.set_packet_callback(self._make_packet_callback(room_name))
        self.room_destinations[room_name] = dest
        RNS.log("Room '{}' destination: {}".format(
            room_name, RNS.prettyhexrep(dest.hash)))

    def _make_packet_callback(self, room_name):
        def callback(message, packet):
            self._on_packet(message, packet, room_name)
        return callback

    # ------------------------------------------------------------------
    # Packet receive
    # ------------------------------------------------------------------

    def _on_packet(self, message, packet, room_name):
        try:
            data = json.loads(message.decode("utf-8"))
            callsign = data.get("from", "Unknown")
            sender_hash = data.get("sender_hash", "unknown")

            if sender_hash != "unknown" and sender_hash not in self.peers:
                self.peers[sender_hash] = callsign
                self._emit({
                    "event":    "peer_appeared",
                    "callsign": callsign,
                    "hash":     sender_hash
                })

            self._emit({
                "event": "message",
                "room":  room_name,
                "from":  callsign,
                "body":  data.get("body", ""),
                "ts":    data.get("ts", int(time.time()))
            })

        except Exception as e:
            RNS.log("Packet parse error: {}".format(e), RNS.LOG_ERROR)

    # ------------------------------------------------------------------
    # Send
    # ------------------------------------------------------------------

    def send(self, room, body):
        if room not in self.room_destinations:
            RNS.log("Unknown room: {}".format(room), RNS.LOG_WARNING)
            return
        payload = json.dumps({
            "from": self.callsign,
            "uid":  self.uid,
            "room": room,
            "body": body,
            "ts":   int(time.time()),
            "sender_hash": RNS.prettyhexrep(self.announce_destination.hash)
        }).encode("utf-8")
        packet = RNS.Packet(self.room_destinations[room], payload)
        packet.send()

    # ------------------------------------------------------------------
    # Announce
    # ------------------------------------------------------------------

    def _announce(self):
        app_data = json.dumps({
            "callsign": self.callsign,
            "uid":      self.uid
        }).encode("utf-8")
        self.announce_destination.announce(app_data=app_data)
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
        self._emit({"event": "ready"})

        while True:
            conn, addr = srv.accept()
            RNS.log("Java client connected from {}".format(addr))
            with self._lock:
                self.clients.append(conn)
            # Send ready to newly connected client
            try:
                conn.sendall((json.dumps({"event": "ready"}) + "\n").encode("utf-8"))
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

    def _handle_command(self, cmd):
        c = cmd.get("cmd")
        if c == "send":
            self.send(cmd.get("room", "All Chat"), cmd.get("body", ""))
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
