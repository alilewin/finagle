package com.twitter.finagle.thrift.exp.partitioning

import com.twitter.finagle.thrift.exp.partitioning.PartitioningStrategy._
import com.twitter.finagle.thrift.exp.partitioning.ThriftCustomPartitioningService.PartitioningStrategyException
import com.twitter.scrooge.{ThriftMethodIface, ThriftStructIface}
import com.twitter.util.{Future, Try}
import scala.collection.mutable

object PartitioningStrategy {

  /**
   *
   * A request merger defines the merger function needed to define a Finagle partitioning
   * service for Thrift services.
   *
   * This is used for [[HashingPartitioningStrategy]], which applies a consistent
   * hashing partitioning strategy under the hood. Note that more than one request may
   * fall in the same HashNode, even though they're assigned with different hashing keys.
   * It merges a collection of requests to a final request which
   * matches the Thrift IDL definition. RequestMerger is registered through
   * [[RequestMergerRegistry]]
   *
   * for example:
   * {{{
   *   i32 example(1: string in)
   *
   *   val requestMerger: RequestMerger[Example.Args] = listArgs =>
   *     Example.Args(in = listArgs.map(_.in).mkString(";"))
   * }}}
   */
  type RequestMerger[Req <: ThriftStructIface] = Seq[Req] => Req

  /**
   * A response merger defines the merger function needed to define a Finagle partitioning
   * service for Thrift services.
   *
   * This is used for messaging fan-out, it merges a collection of successful
   * responses and a collection of failures to a final response which matches
   * the Thrift IDL definition. ResponseMerger is registered through
   * [[ResponseMergerRegistry]].
   *
   * Failing sub-responses are expected to be handled by the application (proper logging,
   * exception handling, etc),
   *
   * for example:
   * {{{
   *   string example(1: i32 a)
   *
   *   val responseMerger: ResponseMerger[String] = (successes, failures) =>
   *     if (successes.nonEmpty) Return(successes.mkString(";"))
   *     else Throw(failures.head)
   * }}}
   */
  type ResponseMerger[Rep] = (Seq[Rep], Seq[Throwable]) => Try[Rep]

  object RequestMergerRegistry {

    /**
     * Create an empty RequestMergerRegistry.
     * @note The created RequestMergerRegistry is NOT thread safe, it carries an assumption
     *       that registries are written during client initialization.
     */
    def create(): RequestMergerRegistry = new RequestMergerRegistry()
  }

  /**
   * Maintain a map of method name to method's [[RequestMerger]].
   */
  class RequestMergerRegistry {

    // reqMerger Map is not thread safe, assuming `add` only be called during client
    // initialization and the reqMerger remains the same as request threads `get` from it.
    private[this] val reqMergers: mutable.Map[String, RequestMerger[ThriftStructIface]] =
      mutable.Map.empty

    /**
     * Register a RequestMerger for a ThriftMethod.
     * @param method  ThriftMethod is a method endpoint defined in a thrift service
     * @param merger  see [[RequestMerger]]
     */
    def add[Req <: ThriftStructIface](
      method: ThriftMethodIface,
      merger: RequestMerger[Req]
    ): RequestMergerRegistry = {
      reqMergers += (method.name -> merger.asInstanceOf[RequestMerger[ThriftStructIface]])
      this
    }

    /**
     * Get a RequestMerger for a ThriftMethod.
     * @param methodName  The Thrift method name
     */
    def get(methodName: String): Option[RequestMerger[ThriftStructIface]] =
      reqMergers.get(methodName)
  }

  object ResponseMergerRegistry {

    /**
     * Create an empty ResponseMergerRegistry.
     * @note The created ResponseMergerRegistry is NOT thread safe, it carries an assumption
     *       that registries are written during client initialization.
     */
    def create(): ResponseMergerRegistry = new ResponseMergerRegistry()
  }

  /**
   * Maintain a map of method name to method's [[ResponseMerger]].
   */
  class ResponseMergerRegistry {

    // repMergers Map is not thread safe, assuming `add` is only called during client
    // initialization and the repMergers remains the same as request threads `get` from it.
    private[this] val repMergers: mutable.Map[String, ResponseMerger[Any]] = mutable.Map.empty

    /**
     * Register a ResponseMerger for a ThriftMethod.
     * @param method  ThriftMethod is a method endpoint defined in a thrift service
     * @param merger  see [[ResponseMerger]]
     */
    def add[Rep](method: ThriftMethodIface, merger: ResponseMerger[Rep]): ResponseMergerRegistry = {
      repMergers += (method.name -> merger.asInstanceOf[ResponseMerger[Any]])
      this
    }

    /**
     * Get a ResponseMerger for a ThriftMethod.
     * @param methodName  The Thrift method name
     */
    def get(methodName: String): Option[ResponseMerger[Any]] =
      repMergers.get(methodName)
  }
}

/**
 * Service partitioning strategy to apply on the clients in order to let clients route
 * requests accordingly. Two particular partitioning strategies are going to be supported,
 * [[HashingPartitioningStrategy]] and [[CustomPartitioningStrategy]].
 * Either one will need developers to provide a concrete function to give each request an
 * indicator of destination, for example a hashing key or a partition address.
 * Messaging fan-out is supported by leveraging RequestMerger and ResponseMerger.
 */
sealed trait PartitioningStrategy {

  /**
   * A ResponseMergerRegistry implemented by client to supply [[ResponseMerger]]s.
   * For message fan-out cases.
   * @see [[ResponseMerger]]
   */
  val responseMergerRegistry: ResponseMergerRegistry = ResponseMergerRegistry.create()
}

sealed trait HashingPartitioningStrategy extends PartitioningStrategy {

  /**
   * A RequestMergerRegistry implemented by client to supply [[RequestMerger]]s.
   * For message fan-out cases.
   * @see [[RequestMerger]]
   */
  val requestMergerRegistry: RequestMergerRegistry = RequestMergerRegistry.create()
}

sealed trait CustomPartitioningStrategy extends PartitioningStrategy {

  /**
   * Gets the logical partition identifier from a host identifier, host identifiers are derived
   * from [[ZkMetadata]] shardId. Indicates which logical partition a physical host belongs to,
   * multiple hosts can belong to the same partition, for example:
   * {{{
   *  val getLogicalPartition: Int => Int = {
   *    case a if Range(0, 10).contains(a) => 0
   *    case b if Range(10, 20).contains(b) => 1
   *    case c if Range(20, 30).contains(c) => 2
   *    case _ => throw ...
   *  }
   * }}}
   * The default is each host is a partition.
   */
  def getLogicalPartition(instance: Int): Int = instance
}
private[partitioning] object Disabled extends PartitioningStrategy

object ClientHashingStrategy {

  /**
   * Thrift requests not specifying hashing keys will fall in here. This allows a
   * Thrift/ThriftMux partition aware client to serve a part of endpoints of a service.
   * Un-specified endpoints should not be called from this client, otherwise, throw
   * [[com.twitter.finagle.partitioning.ConsistentHashPartitioningService.NoPartitioningKeys]].
   */
  val defaultHashingKeyAndRequest: ThriftStructIface => Map[Any, ThriftStructIface] = args =>
    Map(None -> args)
}

/**
 * An API to set a consistent hashing partitioning strategy for a Thrift/ThriftMux Client.
 */
abstract class ClientHashingStrategy extends HashingPartitioningStrategy {
  // input: original thrift request
  // output: a Map of hashing keys and split requests
  type ToPartitionedMap = PartialFunction[ThriftStructIface, Map[Any, ThriftStructIface]]

  /**
   * A PartialFunction implemented by client that provides the partitioning logic on
   * a request. It takes a Thrift object request, and returns a Map of hashing keys to
   * sub-requests. If no fan-out needs, it should return one element: hashing key to the
   * original request.
   * This PartialFunction can take multiple Thrift request types of one Thrift service
   * (different method endpoints of one service).
   */
  def getHashingKeyAndRequest: ToPartitionedMap
}

object ClientCustomStrategy {

  /**
   * Thrift requests not specifying partition ids will fall in here. This allows a
   * Thrift/ThriftMux partition aware client to serve a part of endpoints of a service.
   * Un-specified endpoints should not be called from this client, otherwise, throw
   * [[com.twitter.finagle.thrift.exp.partitioning.ThriftCustomPartitioningService.PartitioningStrategyException]].
   */
  val defaultPartitionIdAndRequest: ThriftStructIface => Future[Map[Int, ThriftStructIface]] =
    _ =>
      Future.exception(
        new PartitioningStrategyException(
          "An unspecified endpoint has been applied to the partitioning service, please check " +
            "your ClientCustomStrategy.getPartitionIdAndRequest see if the endpoint is defined"))
}

/**
 * An API to set a custom partitioning strategy for a Thrift/ThriftMux Client.
 */
abstract class ClientCustomStrategy extends CustomPartitioningStrategy {
  // input: original thrift request
  // output: Future Map of partition ids and split requests
  type ToPartitionedMap = PartialFunction[ThriftStructIface, Future[Map[Int, ThriftStructIface]]]

  /**
   * A PartialFunction implemented by client that provides the partitioning logic on
   * a request. It takes a Thrift object request, and returns Future Map of partition ids to
   * sub-requests. If no fan-out needs, it should return one element: partition id to the
   * original request.
   * This PartialFunction can take multiple Thrift request types of one Thrift service
   * (different method endpoints of one service).
   *
   * @note  When updating the partition topology dynamically, there is a potential one-time
   *        mismatch if a Service Discovery update happens after getPartitionIdAndRequest.
   */
  def getPartitionIdAndRequest: ToPartitionedMap
}
