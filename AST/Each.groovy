package AST
public class Each {
  def name
  def path
  def children
  Each(name, path){
    this.name = name
    this.path = path
    children = []
  }
  def push(o){
    this.children.push(o)
  }
  def toGroovy(results){
    def quotedPath = path.collect { "\"$it\"" }
    results.push("def fn = { localData ->\n")
    results.push("def dataBackup = data\n")
    results.push("data = localData\n")
    children.each { it.toGroovy(results) }
    results.push("data = dataBackup\n")

    results.push("}\n")
    results.push("runtime.each(data, \"$name\", $quotedPath, fn)\n")
  }
}
