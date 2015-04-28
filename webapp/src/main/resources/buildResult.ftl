<#-- @ftlvariable name="builds" type="java.util.List<org.renjin.ci.datastore.PackageBuild>" -->
<#include "base.ftl">

<@scaffolding>


<div class="row">

    <div class="col-md-3">

        <div class="panel panel-default">
            <!-- Default panel contents -->
            <div class="panel-heading">Build History</div>
            <div class="list-group">
                <#list builds?reverse as build>
                    <a href="${build.buildNumber}" class="list-group-item">#${build.buildNumber}
                        <small class="text-muted"> with Renjin ${build.renjinVersion}</small>
                        <#if build.succeeded>
                            <span class="glyphicon glyphicon-ok-sign text-success pull-right" aria-hidden="true"></span>
                        <#else>
                            <span class="glyphicon glyphicon-remove-sign text-danger pull-right" aria-hidden="true"></span>
                        </#if>
                    </a>
                </#list>
            </div>
        </div>

        <ul class="nav nav-pills nav-stacked">

        </ul>
    </div>
    <div class="col-md-9">

        <h1>${packageName} ${version}</h1>

        <h2>Build #${buildNumber}</h2>

        <p>${build.outcome!"Started"} <#if startTime??>on ${startTime?datetime} </#if>against Renjin ${build.renjinVersion}</p>

        <#if log??>
            <pre class="log">${log}</pre>
        <#else>
            <div class="alert alert-warning">Build log is not available.</div>
        </#if>
        
    </div>

</div>
</@scaffolding>