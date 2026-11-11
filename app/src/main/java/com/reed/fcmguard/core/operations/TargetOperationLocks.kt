package com.reed.fcmguard.core.operations

interface TargetOperationLock {
    fun <T> withLock(target: String, block: () -> T): T
}

object TargetOperationLocks : TargetOperationLock {
    private val locks = HashMap<String, Any>()

    override fun <T> withLock(target: String, block: () -> T): T {
        val lock = synchronized(locks) { locks.getOrPut(target) { Any() } }
        return synchronized(lock, block)
    }

    fun <T> withLocks(targets: Collection<String>, block: () -> T): T {
        val ordered = targets.distinct().sorted()
        fun lockAt(index: Int): T = if (index == ordered.size) {
            block()
        } else {
            withLock(ordered[index]) { lockAt(index + 1) }
        }
        return lockAt(0)
    }
}
