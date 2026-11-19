"""Run the full multi-node demo: forest simulator -> N in-process edge agents
-> real backend -> dashboard + Telegram + correlator.

Usage:
  python simulator/run_nodes.py --scenario gunfire_burst          # real time
  python simulator/run_nodes.py --scenario quiet_day --accelerate 60

Each virtual node gets its own SimSource + EdgeAgent (own decision state,
own offline queue). Ground truth is written to data/sim/<scenario>/.
"""
from __future__ import annotations

import argparse
import logging
import signal
import sys
import threading
import time
from pathlib import Path

import numpy as np

REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO))

from common.config import data_dir, data_file, load_config  # noqa: E402
from common.logging_setup import setup_logging  # noqa: E402
from simulator.forest_sim import ForestSim, default_nodes  # noqa: E402
from ml.window_cache import split_of  # noqa: E402  (unused; doc reference)

log = logging.getLogger("forestear.run_nodes")


class InProcessNode:
    """One virtual node = SimSource + EdgeAgent running in a thread."""

    def __init__(self, sim_node, backend_url: str, agent_kwargs: dict | None = None):
        from edge_agent.agent import EdgeAgent
        from edge_agent.sources import SimSource
        self.sim_node = sim_node
        self.source = SimSource(pace_realtime=False)
        self.agent = EdgeAgent(
            self.source,
            node_id=sim_node.node_id,
            backend_url=backend_url,
            lat=sim_node.lat, lon=sim_node.lon,
            queue_path=str(data_file(f"edge_queue_{sim_node.node_id}.db")),
        )
        self.thread = threading.Thread(target=self._run, daemon=True)

    def _run(self):
        try:
            self.agent.run()
        except Exception as e:  # noqa: BLE001
            log.error("agent %s crashed: %s", self.sim_node.node_id, e)


def main() -> int:
    setup_logging()
    cfg = load_config()
    ap = argparse.ArgumentParser()
    ap.add_argument("--scenario", default="chainsaw_approach")
    ap.add_argument("--nodes", type=int, default=4)
    ap.add_argument("--backend", default=cfg["node"]["backend_url"])
    ap.add_argument("--accelerate", type=float, default=1.0)
    ap.add_argument("--clip-split", default="any", choices=["any", "test"])
    args = ap.parse_args()

    nodes = default_nodes(args.nodes)
    log.info("virtual nodes: %s",
             [(n.node_id, n.lat, n.lon, round(n.clock_offset_s, 3)) for n in nodes])

    runners = [InProcessNode(n, args.backend) for n in nodes]
    for r in runners:
        r.thread.start()
    time.sleep(8)  # let models load

    sim = ForestSim(REPO / "simulator" / "scenarios" / f"{args.scenario}.yaml",
                   nodes, clip_split=args.clip_split)
    sinks = {r.sim_node.node_id: r.source for r in runners}
    gt_dir = data_dir("sim", args.scenario)
    # simulator timestamps are shifted by each node's clock offset when pushed
    # to the agent: we apply the offset in the agent's event timestamps by
    # pushing with sim time; agents use wall-clock UTC for transport, which is
    # NTP-synced in a real deployment.
    gt = sim.run(sinks=sinks, time_scale=args.accelerate,
                 gt_path=gt_dir / "ground_truth.csv")
    for r in runners:
        r.source.finish()
    # give agents a moment to drain queues, then stop
    deadline = time.time() + 20
    while time.time() < deadline and any(r.thread.is_alive() for r in runners):
        if all(r.source.q.empty() for r in runners):
            break
        time.sleep(0.5)
    for r in runners:
        r.agent._running = False
    time.sleep(2)
    log.info("done. ground truth events: %d", len(gt))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
