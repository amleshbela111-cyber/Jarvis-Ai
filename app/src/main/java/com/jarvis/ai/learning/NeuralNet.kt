package com.jarvis.ai.learning

import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random

/** Activation functions. Each one also knows its derivative, which backpropagation needs. */
enum class Activation {
    SIGMOID, TANH, RELU, LINEAR;

    fun f(x: Double): Double = when (this) {
        SIGMOID -> 1.0 / (1.0 + exp(-x))
        TANH -> tanh(x)
        RELU -> if (x > 0) x else 0.0
        LINEAR -> x
    }

    /** Derivative written in terms of the output y = f(x). */
    fun df(y: Double): Double = when (this) {
        SIGMOID -> y * (1.0 - y)
        TANH -> 1.0 - y * y
        RELU -> if (y > 0) 1.0 else 0.0
        LINEAR -> 1.0
    }
}

/** A small fully connected network trained with backpropagation and gradient descent (mean squared error). */
class NeuralNet(sizes: IntArray, private val hidden: Activation, private val output: Activation, seed: Int = 7) {
    private val rng = Random(seed)
    private val layers = sizes.size - 1
    private val weights: Array<Array<DoubleArray>> = Array(layers) { l ->
        val nIn = sizes[l]
        val nOut = sizes[l + 1]
        val limit = sqrt(6.0 / (nIn + nOut))
        Array(nOut) { DoubleArray(nIn) { rng.nextDouble(-limit, limit) } }
    }
    private val biases: Array<DoubleArray> = Array(layers) { l -> DoubleArray(sizes[l + 1]) }

    private fun actFor(l: Int): Activation = if (l == layers - 1) output else hidden

    /** Runs one input through the network. Returns the activation of every layer (input first). */
    fun forward(x: DoubleArray): List<DoubleArray> {
        val acts = ArrayList<DoubleArray>()
        acts.add(x)
        for (l in 0 until layers) {
            val prev = acts.last()
            val act = actFor(l)
            val out = DoubleArray(weights[l].size) { j ->
                var z = biases[l][j]
                for (i in prev.indices) z += weights[l][j][i] * prev[i]
                act.f(z)
            }
            acts.add(out)
        }
        return acts
    }

    fun predict(x: DoubleArray): DoubleArray = forward(x).last()

    /** Trains on every sample once per epoch. Returns the mean loss of the last epoch. */
    fun train(inputs: List<DoubleArray>, targets: List<DoubleArray>, epochs: Int, lr: Double): Double {
        var lastLoss = 0.0
        repeat(epochs) {
            var total = 0.0
            for (s in inputs.indices) {
                val acts = forward(inputs[s])
                val out = acts.last()
                val target = targets[s]
                // Mean squared error and its gradient at the output layer.
                var delta = DoubleArray(out.size)
                for (j in out.indices) {
                    val e = out[j] - target[j]
                    total += e * e / out.size
                    delta[j] = 2.0 * e / out.size * output.df(out[j])
                }
                // Backpropagation: send the error back layer by layer, then do one gradient descent step.
                for (l in layers - 1 downTo 0) {
                    val prev = acts[l]
                    val nextDelta = if (l > 0) DoubleArray(prev.size) else null
                    if (nextDelta != null) {
                        for (i in prev.indices) {
                            var sum = 0.0
                            for (j in delta.indices) sum += weights[l][j][i] * delta[j]
                            nextDelta[i] = sum * hidden.df(prev[i])
                        }
                    }
                    for (j in delta.indices) {
                        for (i in prev.indices) weights[l][j][i] -= lr * delta[j] * prev[i]
                        biases[l][j] -= lr * delta[j]
                    }
                    if (nextDelta != null) delta = nextDelta
                }
            }
            lastLoss = total / inputs.size
            total = 0.0
        }
        return lastLoss
    }
}

