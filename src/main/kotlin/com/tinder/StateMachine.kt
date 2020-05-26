package com.tinder

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.reflect.KClass

class StateMachine<STATE : Any, EVENT : Any, SIDE_EFFECT : Any> private constructor(
        private val graph: Graph<STATE, EVENT, SIDE_EFFECT>
) {
    private val lock = SynchronizedObject()
    private val currentStateReference = atomic(createStateRef(graph.initialState))

    val state: STATE
        get() = currentStateReference.value.state

    private var currentStateDefinition: StateRef<STATE, EVENT, SIDE_EFFECT>
        get() = currentStateReference.value
        private set(value) {
            currentStateReference.value = value
        }

    fun transition(event: EVENT): Transition<STATE, EVENT, SIDE_EFFECT> {

        val (transition, t) = synchronized(lock) {
            val fromState = currentStateDefinition
            val transitionDefinition = fromState.getTransition(event)
            if (transitionDefinition.transition is Transition.Valid) {
                currentStateDefinition = transitionDefinition.stateDefinition
            }
            Pair(fromState, transitionDefinition)
        }
        t.transition.notifyOnTransition()
        if (t.transition is Transition.Valid) {
            currentStateDefinition.notifyOnEnter(event)
            transition.notifyOnExit(event)
        }
        return t.transition
    }

    fun with(init: GraphBuilder<STATE, EVENT, SIDE_EFFECT>.() -> Unit): StateMachine<STATE, EVENT, SIDE_EFFECT> {
        return create(graph.copy(initialState = state), init)
    }

    private fun StateRef<STATE, EVENT, SIDE_EFFECT>.getTransition(event: EVENT): TransitionDefinition<STATE, EVENT, SIDE_EFFECT> {
        val transitions = currentStateDefinition.stateDefinition
                .transitions.toList()
                .sortedByDescending { it.first.complexity() }
        for ((eventMatcher, createTransitionTo) in transitions) {
            if (eventMatcher.matches(event)) {
                val (toState, sideEffect) = createTransitionTo(this.state, event)
                val newStateDefinition = createStateRef(toState)
                return TransitionDefinition(Transition.Valid(this.state, event, toState, sideEffect), newStateDefinition)
            }
        }
        return TransitionDefinition(Transition.Invalid(this.state, event), currentStateDefinition)
    }

    private fun STATE.getMatchingDefinition() = graph.stateDefinitions
            .asIterable()
            .sortedByDescending { it.key.complexity() }
            .filter { it.key.matches(this) }
            .map { it.value }
            .firstOrNull() ?: error("Missing definition for state ${this::class.simpleName}!")


    private fun (StateRef<STATE, EVENT, SIDE_EFFECT>).notifyOnEnter(cause: EVENT) {
        this.stateDefinition.onEnterListeners.forEach { it(state, cause) }
    }

    private fun (StateRef<STATE, EVENT, SIDE_EFFECT>).notifyOnExit(cause: EVENT) {
        this.stateDefinition.onExitListeners.forEach { it(state, cause) }
    }

    private fun Transition<STATE, EVENT, SIDE_EFFECT>.notifyOnTransition() {
        graph.onTransitionListeners.forEach { it(this) }
    }

    @Suppress("UNUSED")
    sealed class Transition<out STATE : Any, out EVENT : Any, out SIDE_EFFECT : Any> {
        abstract val fromState: STATE
        abstract val event: EVENT

        data class Valid<out STATE : Any, out EVENT : Any, out SIDE_EFFECT : Any> internal constructor(
                override val fromState: STATE,
                override val event: EVENT,
                val toState: STATE,
                val sideEffect: SIDE_EFFECT?
        ) : Transition<STATE, EVENT, SIDE_EFFECT>()

        data class Invalid<out STATE : Any, out EVENT : Any, out SIDE_EFFECT : Any> internal constructor(
                override val fromState: STATE,
                override val event: EVENT
        ) : Transition<STATE, EVENT, SIDE_EFFECT>()
    }

    private data class TransitionDefinition<STATE : Any, EVENT : Any, SIDE_EFFECT : Any>(val transition: Transition<STATE, EVENT, SIDE_EFFECT>,
                                                                                         val stateDefinition: StateRef<STATE, EVENT, SIDE_EFFECT>)

    private data class StateRef<STATE : Any, EVENT : Any, SIDE_EFFECT : Any>(val state: STATE, val stateDefinition: Graph.State<STATE, EVENT, SIDE_EFFECT>)
    private fun createStateRef(state: STATE): StateRef<STATE, EVENT, SIDE_EFFECT> {
        return StateRef(state, state.getMatchingDefinition())
    }

    data class Graph<STATE : Any, EVENT : Any, SIDE_EFFECT : Any>(
            val initialState: STATE,
            val stateDefinitions: Map<Matcher<STATE, STATE>, State<STATE, EVENT, SIDE_EFFECT>>,
            val onTransitionListeners: List<(Transition<STATE, EVENT, SIDE_EFFECT>) -> Unit>
    ) {

        class State<STATE : Any, EVENT : Any, SIDE_EFFECT : Any> internal constructor() {
            val onEnterListeners = mutableListOf<(STATE, EVENT) -> Unit>()
            val onExitListeners = mutableListOf<(STATE, EVENT) -> Unit>()
            val transitions = linkedMapOf<Matcher<EVENT, EVENT>, (STATE, EVENT) -> TransitionTo<STATE, SIDE_EFFECT>>()

            data class TransitionTo<out STATE : Any, out SIDE_EFFECT : Any> internal constructor(
                    val toState: STATE,
                    val sideEffect: SIDE_EFFECT?
            )
        }
    }

    class Matcher<T : Any, out R : T> private constructor(private val clazz: KClass<R>) {

        private val predicates = mutableListOf<(T) -> Boolean>({ clazz.isInstance(it) })

        fun where(predicate: R.() -> Boolean): Matcher<T, R> = apply {
            predicates.add {
                @Suppress("UNCHECKED_CAST")
                (it as R).predicate()
            }
        }

        fun whereAll(predicateList: List<R.() -> Boolean>): Matcher<T, R> = apply {
            predicateList.forEach { where(it) }
        }

        fun matches(value: T) = predicates.all { it(value) }
        fun complexity() = predicates.size

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Matcher<*, *>) return false

            if (predicates != other.predicates) return false

            return true
        }

        override fun hashCode(): Int {
            return predicates.hashCode()
        }

        companion object {
            fun <T : Any, R : T> any(clazz: KClass<R>): Matcher<T, R> = Matcher(clazz)

            inline fun <T : Any, reified R : T> any(): Matcher<T, R> = any(R::class)

            inline fun <T : Any, reified R : T> eq(value: R): Matcher<T, R> = any<T, R>().where { this == value }
        }

    }

    @DslMarker
    annotation class GraphMarker

    @DslMarker
    annotation class EventStateMarker

    @GraphMarker
    class GraphBuilder<STATE : Any, EVENT : Any, SIDE_EFFECT : Any>(
            graph: Graph<STATE, EVENT, SIDE_EFFECT>? = null
    ) {
        private var initialState = graph?.initialState
        private val stateDefinitions = LinkedHashMap(graph?.stateDefinitions ?: emptyMap())
        private val onTransitionListeners = ArrayList(graph?.onTransitionListeners ?: emptyList())

        fun initialState(initialState: STATE) {
            this.initialState = initialState
        }

        fun <S : STATE> state(
                stateMatcher: Matcher<STATE, S>,
                init: StateDefinitionBuilder<S>.() -> Unit
        ) {
            val builder = StateDefinitionBuilder<S>().apply(init)
            stateMatcher.whereAll(builder.conditions.toList())
            stateDefinitions[stateMatcher] = builder.build()
        }

        inline fun <reified S : STATE> state(noinline init: StateDefinitionBuilder<S>.() -> Unit) {
            state(Matcher.any(), init)
        }

        inline fun <reified S : STATE> state(state: S, noinline init: StateDefinitionBuilder<S>.() -> Unit) {
            state(Matcher.eq<STATE, S>(state), init)
        }

        fun onTransition(listener: (Transition<STATE, EVENT, SIDE_EFFECT>) -> Unit) {
            onTransitionListeners.add(listener)
        }

        fun build(): Graph<STATE, EVENT, SIDE_EFFECT> {
            return Graph(requireNotNull(initialState), stateDefinitions.toMap(), onTransitionListeners.toList())
        }

        @EventStateMarker
        inner class StateDefinitionBuilder<S : STATE> {

            private val stateDefinition = Graph.State<STATE, EVENT, SIDE_EFFECT>()
            val conditions = mutableListOf<S.() -> Boolean>()

            inline fun <reified E : EVENT> any(): Matcher<EVENT, E> = Matcher.any()

            inline fun <reified R : EVENT> eq(value: R): Matcher<EVENT, R> = Matcher.eq(value)

            fun <E : EVENT> on(
                    eventMatcher: Matcher<EVENT, E>,
                    createTransitionTo: S.(E) -> Graph.State.TransitionTo<STATE, SIDE_EFFECT>
            ) {
                stateDefinition.transitions[eventMatcher] = { state, event ->
                    @Suppress("UNCHECKED_CAST")
                    createTransitionTo((state as S), event as E)
                }
            }

            inline fun <reified E : EVENT> on(
                    noinline createTransitionTo: S.(E) -> Graph.State.TransitionTo<STATE, SIDE_EFFECT>
            ) {
                return on(any(), createTransitionTo)
            }

            inline fun <reified E : EVENT> on(
                    event: E,
                    noinline createTransitionTo: S.(E) -> Graph.State.TransitionTo<STATE, SIDE_EFFECT>
            ) {
                return on(eq(event), createTransitionTo)
            }

            @Suppress("UNUSED")
            inline fun <reified E : EVENT> withCondition(
                    noinline init: TransitionBuilder<S, E>.() -> Unit
            ) {
                val transactionBuilder = TransitionBuilder<S, E>().apply(init)
                transactionBuilder.transitionTo
                return on(any(), transactionBuilder.transitionTo)
            }

            @Suppress("UNUSED")
            inline fun <reified E : EVENT> withCondition(
                    event: E,
                    noinline init: TransitionBuilder<S, E>.() -> Unit
            ) {
                val transactionBuilder = TransitionBuilder<S, E>().apply(init)
                return on(eq(event).whereAll(transactionBuilder.conditions), transactionBuilder.transitionTo)
            }


            fun onEnter(listener: S.(EVENT) -> Unit) = with(stateDefinition) {
                onEnterListeners.add { state, cause ->
                    @Suppress("UNCHECKED_CAST")
                    listener(state as S, cause)
                }
            }

            fun stateCondition(condition: S.() -> Boolean) =
                    conditions.add(condition)


            fun onExit(listener: S.(EVENT) -> Unit) = with(stateDefinition) {
                onExitListeners.add { state, cause ->
                    @Suppress("UNCHECKED_CAST")
                    listener(state as S, cause)
                }
            }

            @EventStateMarker
            inner class TransitionBuilder<S : STATE, E : EVENT> {
                val conditions = mutableListOf<EVENT.() -> Boolean>()
                lateinit var transitionTo: S.(E) -> Graph.State.TransitionTo<STATE, SIDE_EFFECT>

                @Suppress("UNUSED")
                fun eventCondition(condition: EVENT.() -> Boolean) = conditions.add(condition)

                @Suppress("UNUSED")
                fun transition(function: S.(EVENT) -> Graph.State.TransitionTo<STATE, SIDE_EFFECT>) {
                    this.transitionTo = function
                }

                @Suppress("UNUSED") // The unused warning is probably a compiler bug.
                fun S.transitionTo(state: STATE, sideEffect: SIDE_EFFECT? = null) =
                        Graph.State.TransitionTo(state, sideEffect)

                @Suppress("UNUSED") // The unused warning is probably a compiler bug.
                fun S.dontTransition(sideEffect: SIDE_EFFECT? = null) = transitionTo(this, sideEffect)

            }

            fun build() = stateDefinition

            @Suppress("UNUSED") // The unused warning is probably a compiler bug.
            fun S.transitionTo(state: STATE, sideEffect: SIDE_EFFECT? = null) =
                    Graph.State.TransitionTo(state, sideEffect)

            @Suppress("UNUSED") // The unused warning is probably a compiler bug.
            fun S.dontTransition(sideEffect: SIDE_EFFECT? = null) = transitionTo(this, sideEffect)
        }
    }

    companion object {
        fun <STATE : Any, EVENT : Any, SIDE_EFFECT : Any> create(
                init: GraphBuilder<STATE, EVENT, SIDE_EFFECT>.() -> Unit
        ): StateMachine<STATE, EVENT, SIDE_EFFECT> {
            return create(null, init)
        }

        private fun <STATE : Any, EVENT : Any, SIDE_EFFECT : Any> create(
                graph: Graph<STATE, EVENT, SIDE_EFFECT>?,
                init: GraphBuilder<STATE, EVENT, SIDE_EFFECT>.() -> Unit
        ): StateMachine<STATE, EVENT, SIDE_EFFECT> {
            return StateMachine(GraphBuilder(graph).apply(init).build())
        }
    }
}