package org.interscity.htc
package core.scoring

import org.interscity.htc.core.entity.state.BaseState

/**
 * Um trait genérico para funções que calculam uma pontuação (score) baseada
 * no estado de um agente. A pontuação é um Double, representando utilidade,
 * custo, benefício, etc.
 *
 * @tparam T O tipo de estado (que deve herdar de BaseState) que esta função pode avaliar.
 */
trait ScoreFunction[T <: BaseState] extends Serializable {

  /**
   * Calcula a pontuação para um determinado estado.
   * @param state O estado atual do agente.
   * @return Um valor Double representando a pontuação. Valores mais altos são geralmente melhores.
   */
  def calculateScore(state: T): Double
}