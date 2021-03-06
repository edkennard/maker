package maker.task

import maker.task.compile.CompileTask
import maker.task.tasks.RunUnitTestsTask


object Dependency{

  case class Edge(upstream : Task, downstream : Task){
    val nodes = Set(downstream, upstream)
      override def toString = upstream + " -> " + downstream
    def contains(node : Task) = nodes.contains(node)
  }
  object Edge{
    def edges(
      task : Task, 
      extraUpstreamTasksMatcher : PartialFunction[Task, Set[Task]],
      extraDownstreamTasksMatcher : PartialFunction[Task, Set[Task]]
    ) = {
      val upstreams = (task.upstreamTasks ++ extraUpstreamTasksMatcher.lift(task).getOrElse(Set.empty)).map(Edge(_, task))
      val downstreams = extraDownstreamTasksMatcher.lift(task).getOrElse(Set.empty).map(Edge(task, _))
      upstreams ++ downstreams
    }
  }

  case class Graph(nodes : Set[Task], edges : Set[Edge] = Set.empty) {
    require(edges.flatMap(_.nodes).subsetOf(nodes), "Invalid graph, ordering has extra nodes " + this)
    def this(edges : Set[Edge]) = this(edges.flatMap(_.nodes), edges)


    override def toString = {
      val buffer = new StringBuffer
      val sortedNodes = nodes.toList.sortWith(_.toString < _.toString)
      buffer.append("  Nodes:\n")
      buffer.append(sortedNodes.mkString("    ", "\n    ", "\n"))
      buffer.append("  Edges:\n")
      val sortedEdges = edges.toList.sortWith(_.toString < _.toString)
      buffer.append(sortedEdges.mkString("    ", "\n    ", "\n"))
      buffer.toString
    }

    def leaves = nodes.filterNot(edges.map(_.downstream))
    def innerNodes = nodes.filterNot(leaves)
    def filter(predicate : Task => Boolean) = Graph(
      nodes.filter(predicate),
      edges.filter{o => predicate(o.downstream) && predicate(o.upstream)}
    )
    def filterNot(predicate : Task => Boolean) = Graph(
      nodes.filterNot(predicate),
      edges.filterNot{o => predicate(o.downstream) || predicate(o.upstream)}
    )
    def upstreams(node : Task) = edges.filter(_.downstream == node).map(_.upstream)
    def ++ (rhs : Graph) = Graph(nodes ++ rhs.nodes, edges ++ rhs.edges)
    def --(nodes : Set[Task]) = filterNot(nodes)
    def - (node : Task) = {
      filterNot(Set(node))
    }
    def size = nodes.size
    def subGraphOf(rhs : Graph) = nodes.subsetOf(rhs.nodes) && edges.subsetOf(rhs.edges)
    def includesCompileTask = nodes.exists {
      case _ : CompileTask => true
      case _ => false
    }
    def includesTestTask = nodes.exists {
      case _ : RunUnitTestsTask => true
      case _ => false
    }
  }

  object Graph{
    def empty = Graph(Set.empty, Set.empty)

    def transitiveClosure(
      tasks : Iterable[Task],
      extraUpstreamTasks : PartialFunction[Task, Set[Task]],
      extraDownstreamTasks : PartialFunction[Task, Set[Task]]
    ) : Graph = {
      def recurse(acc : Graph) : Graph = {
        val newEdges = acc.leaves.flatMap(Edge.edges(_, extraUpstreamTasks, extraDownstreamTasks)) -- acc.edges
        val newNodes = newEdges.flatMap(_.nodes)
        if (newEdges.isEmpty)
          acc
        else
          recurse(Graph(acc.nodes ++ newNodes, acc.edges ++ newEdges))
      }
      recurse(Graph(tasks.toSet, Set.empty))
    }

    def apply(task : Task) : Graph = Graph(Set(task), Set.empty)
  }
}
