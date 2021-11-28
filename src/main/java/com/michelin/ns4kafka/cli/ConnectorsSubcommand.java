package com.michelin.ns4kafka.cli;

import com.michelin.ns4kafka.cli.models.ApiResource;
import com.michelin.ns4kafka.cli.models.ObjectMeta;
import com.michelin.ns4kafka.cli.models.Resource;
import com.michelin.ns4kafka.cli.services.ApiResourcesService;
import com.michelin.ns4kafka.cli.services.FormatService;
import com.michelin.ns4kafka.cli.services.LoginService;
import com.michelin.ns4kafka.cli.services.ResourceService;
import jakarta.inject.Inject;
import picocli.CommandLine;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

@CommandLine.Command(name = "connectors", description = "Interact with connectors (Pause/Resume/Restart)")
public class ConnectorsSubcommand implements Runnable {

    @CommandLine.ParentCommand
    public KafkactlCommand kafkactlCommand;

    @CommandLine.Parameters(index = "0", description = "(pause | resume | restart)", arity = "1")
    public ConnectorAction action;

    @CommandLine.Parameters(index="1..*", description = "Connector names separated by space (use `ALL` for all connectors)", arity = "1..*")
    public List<String> connectors;

    @Inject
    public LoginService loginService;
    @Inject
    public KafkactlConfig kafkactlConfig;
    @Inject
    public ResourceService resourceService;
    @Inject
    public ApiResourcesService apiResourcesService;
    @Inject
    public FormatService formatService;

    

    @Override
    public void run() {

        boolean authenticated = loginService.doAuthenticate();
        if (!authenticated) {
            throw new UnsupportedOperationException( "Login failed");
        }

        String namespace = kafkactlCommand.optionalNamespace.orElse(kafkactlConfig.getCurrentNamespace());

        // specific case ALL
        if(connectors.stream().anyMatch(s -> s.equalsIgnoreCase("ALL"))){
            ApiResource connectType = apiResourcesService.getResourceDefinitionFromKind("Connector")
                    .orElseThrow(() -> new UnsupportedOperationException( "`Connector` Kind not found in ApiResources Service"));
            connectors = resourceService.listResourcesWithType(connectType, namespace)
                    .stream()
                    .map(resource -> resource.getMetadata().getName())
                    .collect(Collectors.toList());
        }

        List<Resource> changeConnectorResponseList = connectors.stream()
                // prepare request object
                .map(connector -> Resource.builder()
                        .metadata(ObjectMeta.builder()
                                .namespace(namespace)
                                .name(connector)
                                .build())
                        .spec(Map.of("action", action.toString()))
                        .build())
                // execute action on each connectors
                .map(changeConnectorStateRequest -> resourceService.changeConnectorState(namespace, changeConnectorStateRequest.getMetadata().getName(), changeConnectorStateRequest))
                // drop nulls
                .filter(Objects::nonNull)
                .collect(Collectors.toList());


        if (!changeConnectorResponseList.isEmpty()) {
            formatService.displayList("ChangeConnectorState", changeConnectorResponseList, "table");

        }


    }
}

enum ConnectorAction {
    pause,
    resume,
    restart
}
