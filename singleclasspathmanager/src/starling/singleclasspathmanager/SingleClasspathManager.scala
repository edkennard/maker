package starling.singleclasspathmanager

import starling.manager._
import starling.osgimanager.utils.ThreadSafeCachingProxy

/**
 * SingleClasspathManager provides the means to create new instances of, then start, a number of BromptonActivator-s.
 * It creates a private, custom BromptonContext with which each activator's services may be registered.  This context
 * uses the ThreadSafeCachingProxy to create a proxy that may cache method invokation values.
 *
 * @see ThreadSafeCachingProxy
 * @diagram dev/services/starling/docs/Server Activation.png
 * @documented
 */
class SingleClasspathManager(properties:Map[String,String], cacheServices:Boolean, activators:List[Class[_ <: BromptonActivator]]) {
  val props = new Props(properties)
  case class ServiceEntry(klass:Class[_], service:AnyRef, properties:List[ServiceProperty], reference:BromptonServiceReference) {
    private val propertiesSet = properties.toSet
    def hasProperties(predicate:List[ServiceProperty]) = predicate.forall(p=>propertiesSet.contains(p))
  }

  case class TrackerEntry[T](klass:Option[Class[_]], properties:List[ServiceProperty], callback:BromptonServiceCallback[T]) {

    def applyTo(services:List[ServiceEntry]) {
      each { (ref, service) => callback.serviceAdded(ref, service) }
    }

    def each(f:( (BromptonServiceReference,T)=>Unit) ) {
      val services = registry.toList
      val matches = services.filter { reg => reg.hasProperties(properties) && klass.map(_ == reg.klass).getOrElse(true) }
      matches.foreach { reg => f(reg.reference, reg.service.asInstanceOf[T]) }
    }
  }

  def service[T](klass:Class[T]) = {
    registry.toList.filter(_.klass == klass) match {
      case Nil => throw new NoServiceFoundException("No " + klass + " service found")
      case entry :: Nil => entry.service.asInstanceOf[T]
      case many => throw new Exception("There is more than one " + klass + " service")
    }
  }

  var id = 0
  private var started = false
  private val instances = activators.map(_.newInstance)
  private val registry = new scala.collection.mutable.ArrayBuffer[ServiceEntry]()
  private val trackers = new scala.collection.mutable.ArrayBuffer[TrackerEntry[_]]()
  private val context = new BromptonContext() {
    def registerService[T](
      klass:Class[T],
      service:T,
      properties:List[ServiceProperty]=List()) = {
      if (!klass.isAssignableFrom(service.asInstanceOf[AnyRef].getClass)) throw new Exception(service + " is not a " + klass)
      val ref = { id+=1; BromptonServiceReference(id + ":" + klass, List(klass.getName)) }
      val cachingService = if (cacheServices) ThreadSafeCachingProxy.createProxy(klass, service) else service
      val entry = ServiceEntry(klass, cachingService.asInstanceOf[AnyRef], properties, ref)
      registry.append( entry )

      trackers.toList.foreach{ tracker => {
        tracker.applyTo(List(entry))
      }}
      new BromptonServiceRegistration() {
        def unregister() { throw new Exception("Unsupported") }
      }
    }

    def awaitService[T](klass:Class[T]):T = {
      service(klass)
    }

    def createServiceTracker[T](klass:Option[Class[T]], properties:List[ServiceProperty], callback:BromptonServiceCallback[T]) = {
      val trackerEntry = TrackerEntry(klass, properties, callback)
      trackerEntry.applyTo(registry.toList)
      trackers += trackerEntry
      new BromptonServiceTracker[T] {
        def each(f: (T) => Unit) {
          trackerEntry.each { (ref,service) => f(service) }
        }
      }
    }
  }

  /**
   * Starts then initialises each instance of this manager's activator types with a specialised BromptonContext.  The
   * context uses a ThreadSafeCachingProxy.  When each activator has been started and initialised this instance informs
   * its properties instance by invoking its Props#completed method.
   *
   * @throws Exception if this instance is already started.
   * @see ThreadSafeCachingProxy
   */
  def start() {
    this synchronized {
      if (started) throw new Exception("Already started")
      started = true
      val classLoader = classOf[SingleClasspathManager].getClassLoader
      instances.foreach { activator => {
        activator.start(context)
        activator.init(context, props.applyOverrides(classLoader, activator.defaults))
      } }
      props.completed()
    }
  }

  /**
   * Stops this instance and each of its activators.
   *
   * @throws Exception if this instance is not started.
   */
  def stop() {
    this synchronized {
      if (!started) throw new Exception("Not started yet")
      started = false
	  instances.foreach { activator => {
	    activator.stop(context)
	  } }
    }
  }
}
