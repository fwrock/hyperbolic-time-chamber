"""
Traffic analysis algorithms package
"""

# Import absoluto para evitar problemas quando executado como script principal
try:
    from analysis.traffic_analyzer import TrafficAnalyzer
except ImportError:
    # Fallback para import relativo
    from .traffic_analyzer import TrafficAnalyzer

__all__ = ['TrafficAnalyzer']