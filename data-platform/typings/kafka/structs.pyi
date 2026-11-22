from typing import NamedTuple

class TopicPartition(NamedTuple):
    topic: str
    partition: int

class OffsetAndMetadata(NamedTuple):
    offset: int
    metadata: str
