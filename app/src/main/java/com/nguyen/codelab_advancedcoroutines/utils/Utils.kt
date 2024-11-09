package com.nguyen.codelab_advancedcoroutines.utils

data class ComparablePair<A : Comparable<A>, B : Comparable<B>>(
    val first: A, val second: B
) : Comparable<ComparablePair<A, B>> {
    override fun compareTo(other: ComparablePair<A, B>): Int {
        val firstComp = this.first.compareTo(other.first)
        return if (firstComp != 0) firstComp else second.compareTo(other.second)
    }
}