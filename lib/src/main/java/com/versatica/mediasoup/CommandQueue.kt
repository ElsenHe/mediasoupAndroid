package com.versatica.mediasoup

import com.versatica.eventemitter.EventEmitter
import io.reactivex.Observable
import io.reactivex.ObservableEmitter
import io.reactivex.ObservableOnSubscribe

class CommandQueue : EventEmitter() {
    private var logger: Logger = Logger("CommandQueue")

    // Closed flag.
    // @type {Boolean}
    private var closed = false

    // Busy running a command.
    // @type {Boolean}
    private var busy = false

    // Queue for pending commands. Each command is an Object with method,
    // resolve, reject, and other members (depending the case).
    // @type {Array<Object>}
    private var queue: ArrayList<Any> = ArrayList()

    init {
        this.setMaxListeners(Int.MAX_VALUE)
    }

    fun close() {
        this.closed = true
    }

    fun push(method: String, data: Any): Observable<Any> {
        var command = Command(method, data, null)
        logger.debug("push() [method:$method]")
        return Observable.create {
            command.observableEmitter = it
            // Append command to the queue.
            queue.add(command)
            this._handlePendingCommands()
        }
    }

    private fun _handlePendingCommands() {
        if (this.busy)
            return

        // Take the first command.
        if (queue.isEmpty())
            return
        var command = queue[0] as Command
        this.busy = true

        //Execute it.
        this._handleCommand(command)
            .flatMap {
                Observable.create(ObservableOnSubscribe<Any> {
                    this.busy = false
                    // Remove the first command (the completed one) from the queue.
                    queue.removeAt(0)

                    // And continue.
                    this._handlePendingCommands()
                })
            }
            .subscribe()
    }

    private fun _handleCommand(command: Command): Observable<Unit> {
        logger.debug("_handleCommand() [method:$command]")

        if (this.closed) {
            command.observableEmitter!!.onError(Throwable(""))
            return Observable.just(Unit)
        }

        var promiseHolder = PromiseHolder(null)
        this.emit("exec", command, promiseHolder)

        return Observable.just(Unit)
            .flatMap {
                promiseHolder.promise
            }
            .flatMap { result: Any ->
                Observable.create(ObservableOnSubscribe<Unit> {
                    logger.debug("_handleCommand() | command succeeded [method:$command.method]")

                    if (this.closed) {
                        command.observableEmitter!!.onError(Throwable("closed"))
                    } else {
                        // Resolve the command with the given result (if any).
                        command.observableEmitter!!.onNext(result)
                    }
                    //next
                    it.onNext(Unit)
                })
            }
    }

    /**
     * Command define
     *
     * @param method command name
     * @param data command data
     * @param observableEmitter ObservableEmitter<Any> like promise resolve and reject
     */
    data class Command(var method: String, var data: Any, var observableEmitter: ObservableEmitter<Any>?)

    /**
     * PromiseHolder define
     *
     * @param promise  Observable<Any> holder
     */
    data class PromiseHolder(var promise: Observable<Any>?)
}