package org.magery

import org.magery.AST.Each
import org.magery.AST.EmbeddedData
import org.magery.AST.ConditionalDataEmbed
import org.magery.AST.If
import org.magery.AST.Raw
import org.magery.AST.Template
import org.magery.AST.TemplateCall
import org.magery.AST.TemplateChildren
import org.magery.AST.Comment
import org.magery.AST.Unless
import org.magery.AST.Variable
import org.magery.AST.Attributes
import org.magery.AST.TemplateEmbed
import org.apache.commons.lang.StringEscapeUtils
import org.jsoup.Jsoup

class Compiler{
  def static IGNORED_ATTRIBUTES = ["data-tagname", "data-if", "data-unless", "data-each", "data-key"]
  def static BOOLEAN_ATTRIBUTES = ["allowfullscreen", "async", "autofocus", "autoplay", "capture", "controls", "checked", "default", "defer", "disabled", "formnovalidate", "open", "readonly", "hidden", "itemscope", "loop", "muted", "multiple", "novalidate", "open", "required", "reversed", "selected"]
  def static SELF_CLOSING_TAGS = ["area", "base", "br", "col", "embed", "hr", "img", "input", "keygen", "link", "menuitem", "meta", "param", "source", "track", "wbr"]

  def static compileNode(node, output, queue, isRoot){
    if(node instanceof org.jsoup.nodes.TextNode){
      compileTextNode(node, output)
    } else if (node instanceof org.jsoup.nodes.Comment){
      compileComment(node, output)
    } else if(node instanceof org.jsoup.select.Elements){
      node.each { compileNode(it, output, queue, isRoot)}
    } else if (node  instanceof org.jsoup.nodes.Element) {
      compileElement(node, output, queue, isRoot)
    } else{
      throw new Exception(" Unhandeled node type ${node.class} ${node.tagName}")
    }
  }
  
  def static compileComment(node, output){
    output.push(new Comment(node.data))
  }
  def static compileVariables(text){
    def compileVariablesRec
    compileVariablesRec = {str, isText, acc ->
      if(str.size() == 0){ return acc.flatten() }
      if(isText){
        def end = str.indexOf("{{")
        end = end == -1 ? str.size() : end
        def chunk = str.substring(0, end).replace("}}", "")
        return compileVariablesRec(str.substring(end, str.size()), !isText, [acc, new Raw(StringEscapeUtils.escapeHtml(chunk))])
      } else {
        def end = str.indexOf("}}")
        end = end == -1 ? str.size() : end
        def chunk = str.substring(0, end).replace("{{", "")
        return compileVariablesRec(str.substring(end, str.size()), !isText, [acc, new Variable(chunk.trim().tokenize("."))])
      }
    }
    
    def nbOpenedVariableBraces = (text =~ /\{\{/)
    def nbClosedVariableBraces = (text =~ /\}\}/)
    def nbPairesOfBraces = (text =~ /\{\{[^\}]*\}\}/)
    
    if( nbOpenedVariableBraces.size() != nbClosedVariableBraces.size() || 
        nbPairesOfBraces.size() != nbOpenedVariableBraces.size() ){ throw new Exception("In text \"${text.trim()}\" variable should be escaped with \"{{\" before and  \"}}\"")}

    compileVariablesRec(text, true, [])
  }
  
    
  def static compileTextNode(node, output){
    def text = node.wholeText
    def vOuput = compileVariables(text)
    vOuput.each {
      output.push(it)
    }
  }
  def static compileElement(node, output, queue, isRoot){
    def tagName = node.tagName().toLowerCase()
    def isComponent = false
    if(tagName == "template"){
      if(!isRoot){
        queue.push(node)
        return
      }
      isComponent = true
      tagName = node.attr("data-tagname").toLowerCase()
    }

    if(tagName == "template-children"){
      output.push(new TemplateChildren())
      return
    }
    else if ( tagName == "template-embed"){
      output.push(new TemplateEmbed(node.attr("template")))
      return
    }
    if(node.hasAttr("data-each")){
      def eachOutput = new Each(node.attr("data-each"))
      output.push(eachOutput)
      output = eachOutput
    }
    if(node.hasAttr("data-if")){
      def ifOutput = new If(node.attr("data-if"))
      output.push(ifOutput)
      output = ifOutput
    }
    if(node.hasAttr("data-unless")){
      def unlessOutput = new Unless(node.attr("data-unless"))
      output.push(unlessOutput)
      output = unlessOutput
    }

    if(node.tagName().contains("-")){
        def context = node.attributes()
        .findAll({!IGNORED_ATTRIBUTES.contains(it.key)})
        .findAll({it.key.substring(0, 2) != "on"})
        .findAll({it.key != "data-embed"})
        .collectEntries {
          ["$it.key", compileVariables(it.value)]
        }

      def templateName = [new Raw(tagName)]
      if(tagName == "template-call"){
        def templateRaw = node.attr("template")
        templateName = compileVariables(templateRaw)
      }
      def embedData = node.attr("data-embed") == "true"
      def templateOutput = new TemplateCall(templateName, context, embedData)
      output.push(templateOutput)
      node.childNodes().each { childNode ->
        compileNode(childNode, templateOutput, queue, false)
      }
      return
    }

    output.push(new Raw("<$tagName"))
    output.push(new Attributes(node.attributes()))
    
    if(isComponent &&  node.attr("data-embed") != "true"){
      output.push(new ConditionalDataEmbed())
    }

    output.push(new Raw(">"))
    if(!SELF_CLOSING_TAGS.contains(tagName) ){
      node.childNodes().each {
        compileNode(it, output, queue, false)

      }
      output.push(new Raw("</$tagName>"))
    }

  }

  def static compileTree(tree, output){
    def queue = []
    compileNode(tree, output, queue, false)
    while(queue.size() > 0){
      def node = queue.first()
      queue = queue.minus(node)
      def templateName = node.attr("data-tagname")
      if(!templateName.contains("-")){
        throw new Exception("Template name \"$templateName\" is incorrect, it's mandatory that template name include a \"-\" character")
      }
      def template = new Template(templateName, outerHtml(node))
      compileNode(node, template, queue, true)
      output.push(template)
    }
  }

  def static writeNode(node){
    node.toString()
  }
  def static outerHtml(node){
    writeNode(node)
  }

  def static compileFile(fileName, output){
    def str = new File(fileName).text
    def tree = Jsoup.parseBodyFragment(str).body().children()
    compileTree(tree, output)
  }

  def static compileToString(fileName){
    def output = []
    compileFile(fileName, output)

    output
    .collect({it.toGroovy()})
    .flatten()
    .join("")
    .toString()
  }

  def static compileTemplates(fileName, templates = [:]){
    def src = compileToString(fileName)
    def binding = new Binding([templates: templates, runtime: Runtime])
    def gs = new GroovyShell(binding)
    gs.evaluate(src)
    templates
  }

}
