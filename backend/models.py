"""SQLite ORM models: nodes, events, heartbeats, biodiversity_detections,
suppressed_events."""
from __future__ import annotations

import datetime as dt
import os
from pathlib import Path

from sqlalchemy import (Boolean, Column, DateTime, Float, ForeignKey, Integer,
                        String, Text, create_engine)
from sqlalchemy.orm import DeclarativeBase, Session, sessionmaker

from common.config import REPO_ROOT

# FORESTEAR_DB lets tests / load runs point at a throwaway database so the
# demo data in data/backend.db is never polluted.
DB_PATH = Path(os.environ.get("FORESTEAR_DB", str(REPO_ROOT / "data" / "backend.db")))
DB_PATH.parent.mkdir(parents=True, exist_ok=True)
engine = create_engine(f"sqlite:///{DB_PATH}", connect_args={"check_same_thread": False})


class Base(DeclarativeBase):
    pass


class Node(Base):
    __tablename__ = "nodes"
    id = Column(Integer, primary_key=True)
    node_id = Column(String, unique=True, index=True)
    lat = Column(Float)
    lon = Column(Float)
    label = Column(String, default="")
    first_seen = Column(DateTime, default=dt.datetime.utcnow)
    last_seen = Column(DateTime, default=dt.datetime.utcnow)


class Heartbeat(Base):
    __tablename__ = "heartbeats"
    id = Column(Integer, primary_key=True)
    node_id = Column(String, index=True)
    ts = Column(DateTime, default=dt.datetime.utcnow, index=True)
    cpu_pct = Column(Float, nullable=True)
    ram_mb = Column(Float, nullable=True)
    temp_c = Column(Float, nullable=True)
    battery = Column(Float, nullable=True)
    online = Column(Boolean, default=True)
    queue_len = Column(Integer, default=0)
    inference_ms = Column(Float, nullable=True)


class Event(Base):
    __tablename__ = "events"
    id = Column(Integer, primary_key=True)
    node_id = Column(String, index=True)
    ts = Column(DateTime, default=dt.datetime.utcnow, index=True)
    lat = Column(Float)
    lon = Column(Float)
    cls = Column(String, index=True)
    severity = Column(String, index=True)
    confidence = Column(Float)
    persistence = Column(String, default="")
    escalated = Column(Boolean, default=False)
    rms_db = Column(Float)
    per_model_scores = Column(Text)     # JSON
    inference_ms = Column(Float)
    clip_path = Column(String, nullable=True)
    compact_bytes = Column(Integer)
    acknowledged = Column(Boolean, default=False)


class SuppressedEvent(Base):
    __tablename__ = "suppressed_events"
    id = Column(Integer, primary_key=True)
    node_id = Column(String, index=True)
    ts = Column(DateTime, default=dt.datetime.utcnow, index=True)
    cls = Column(String)
    confidence = Column(Float)
    reason = Column(String)
    rms_db = Column(Float)


class BiodiversityDetection(Base):
    __tablename__ = "biodiversity_detections"
    id = Column(Integer, primary_key=True)
    node_id = Column(String, index=True)
    ts = Column(DateTime, default=dt.datetime.utcnow, index=True)
    species = Column(String, index=True)
    confidence = Column(Float)


class SourceEstimate(Base):
    """TDOA / bearing estimates from the multi-node correlator."""
    __tablename__ = "source_estimates"
    id = Column(Integer, primary_key=True)
    ts = Column(DateTime, default=dt.datetime.utcnow, index=True)
    cls = Column(String)
    method = Column(String)          # "tdoa" | "bearing" | "single"
    lat = Column(Float)
    lon = Column(Float)
    radius_m = Column(Float)
    nodes_used = Column(String)
    ground_truth_error_m = Column(Float, nullable=True)
    detail = Column(Text, nullable=True)


Base.metadata.create_all(engine)
SessionLocal = sessionmaker(bind=engine)


def get_db() -> Session:
    db = SessionLocal()
    try:
        return db
    finally:
        pass
