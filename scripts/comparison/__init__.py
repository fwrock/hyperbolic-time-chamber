"""
Comparison module for comparing HTC simulator with reference simulators
"""

from .reference_parser import ReferenceSimulatorParser
from .simulator_comparator import SimulatorComparator

__all__ = ['ReferenceSimulatorParser', 'SimulatorComparator']