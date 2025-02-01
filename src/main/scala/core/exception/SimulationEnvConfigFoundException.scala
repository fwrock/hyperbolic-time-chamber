package org.interscity.htc
package core.exception

class SimulationEnvConfigFoundException(envConfig: String)
    extends RuntimeException(s"Simulation environment configuration $envConfig not found")
