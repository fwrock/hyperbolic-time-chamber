"""
Visualization package for traffic analysis
"""

# Import absoluto para evitar problemas quando executado como script principal
try:
    from visualization.traffic_viz import TrafficVisualizer
except ImportError:
    # Fallback para import relativo
    from .traffic_viz import TrafficVisualizer

__all__ = ['TrafficVisualizer']